package pl.ciphervoice.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.ciphervoice.common.MessageType;
import pl.ciphervoice.common.Protocol;
import pl.ciphervoice.common.WireMessage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ControlServer {
    private static final Logger log = LoggerFactory.getLogger(ControlServer.class);
    static final int JOIN_TIMEOUT_MILLIS = 10_000;

    private final VoiceServerProperties properties;
    private final RoomRegistry rooms;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger senderTags = new AtomicInteger(1000);
    private final SecureRandom random = new SecureRandom();
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile boolean stopping;

    public ControlServer(VoiceServerProperties properties, RoomRegistry rooms, ObjectMapper mapper) {
        this.properties = properties;
        this.rooms = rooms;
        this.mapper = mapper;
    }

    @PostConstruct
    synchronized void start() throws IOException {
        if (stopping || serverSocket != null) {
            throw new IllegalStateException("Serwer TCP został już uruchomiony lub zatrzymany");
        }
        // Bind during bean initialization so Spring cannot report a successful partial startup.
        ServerSocket listener = new ServerSocket(properties.controlPort());
        serverSocket = listener;
        executor.submit(() -> acceptLoop(listener));
        log.info("Kanał sterujący TCP działa na porcie {}", listener.getLocalPort());
    }

    @PreDestroy
    void stop() {
        ServerSocket listener;
        synchronized (this) {
            stopping = true;
            listener = serverSocket;
        }
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException exception) {
                log.debug("Błąd zamykania serwera TCP", exception);
            }
        }
        activeSockets.forEach(ControlServer::closeSocket);
        executor.shutdownNow();
        executor.close();
    }

    int localPort() {
        ServerSocket listener = serverSocket;
        return listener == null ? -1 : listener.getLocalPort();
    }

    private void acceptLoop(ServerSocket listener) {
        try (listener) {
            while (!listener.isClosed()) {
                Socket socket = listener.accept();
                activeSockets.add(socket);
                try {
                    if (stopping) {
                        closeSocket(socket);
                        activeSockets.remove(socket);
                        continue;
                    }
                    socket.setTcpNoDelay(true);
                    socket.setKeepAlive(true);
                    executor.submit(() -> handle(socket));
                } catch (IOException | RejectedExecutionException exception) {
                    activeSockets.remove(socket);
                    closeSocket(socket);
                    if (!stopping) {
                        log.debug("Nie można obsłużyć połączenia TCP", exception);
                    }
                }
            }
        } catch (IOException exception) {
            if (!stopping) {
                log.error("Błąd serwera TCP", exception);
            }
        }
    }

    void handle(Socket socket) {
        activeSockets.add(socket);
        ClientSession session = null;
        RoomRegistry.Change joinedChange = null;
        try (socket;
             ControlFrameReader reader = new ControlFrameReader(socket.getInputStream());
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8))) {
            try {
                if (stopping) {
                    return;
                }
                socket.setSoTimeout(JOIN_TIMEOUT_MILLIS);
                String firstLine = reader.readFrame();
                if (firstLine == null) {
                    return;
                }
                WireMessage join = mapper.readValue(firstLine, WireMessage.class);
                if (join == null || join.type != MessageType.JOIN) {
                    throw new IllegalArgumentException("Pierwszą wiadomością musi być JOIN");
                }

                byte[] relayToken = new byte[Protocol.RELAY_TOKEN_BYTES];
                random.nextBytes(relayToken);
                session = new ClientSession(UUID.randomUUID(), senderTags.incrementAndGet(), relayToken,
                        socket, writer, mapper);
                socket.setSoTimeout(0);
                RoomRegistry.Change change = rooms.join(session, join.room, join.nickname, join.publicKey);
                joinedChange = change;

                WireMessage welcome = roomMessage(MessageType.WELCOME, change);
                welcome.clientId = session.id.toString();
                welcome.senderTag = session.senderTag;
                welcome.relayToken = Base64.getEncoder().encodeToString(session.relayToken);
                publishMembershipChange(change, session, welcome);
                log.info("{} dołączył do pokoju {}", session.nickname, session.room);

                String line;
                while ((line = reader.readFrame()) != null) {
                    WireMessage message = mapper.readValue(line, WireMessage.class);
                    if (message != null && message.type == MessageType.KEY_DISTRIBUTION) {
                        forwardKey(session, message);
                    }
                }
            } catch (Exception exception) {
                if (session == null || session.room == null) {
                    WireMessage error = WireMessage.of(MessageType.ERROR);
                    error.error = exception.getMessage() == null
                            ? "Nie można dołączyć" : exception.getMessage();
                    writer.write(mapper.writeValueAsString(error));
                    writer.newLine();
                    writer.flush();
                } else {
                    log.debug("Rozłączono klienta {}: {}", session.id, exception.getMessage());
                }
            }
        } catch (Exception exception) {
            log.debug("Błąd połączenia sterującego: {}", exception.getMessage());
        } finally {
            if (joinedChange != null) {
                // Release the room's publication slot even if preparing WELCOME failed.
                joinedChange.publish(() -> { });
            }
            if (session != null) {
                RoomRegistry.Change change = rooms.leave(session);
                if (change != null) {
                    publishMembershipChange(change);
                }
                log.info("{} opuścił pokój {}", session.nickname, session.room);
            }
            activeSockets.remove(socket);
        }
    }

    void forwardKey(ClientSession sender, WireMessage message) {
        if (message.targetId == null || message.epoch == null || !validEnvelope(message)) {
            return;
        }
        UUID target;
        try {
            target = UUID.fromString(message.targetId);
        } catch (IllegalArgumentException exception) {
            return;
        }
        int epoch = message.epoch == null ? -1 : message.epoch;
        ClientSession recipient = rooms.distributionTarget(sender, target, epoch);
        if (recipient == null) {
            // JOIN/LEAVE can legitimately overtake an already generated key envelope.
            log.debug("Pominięto nieaktualną lub nieuprawnioną dystrybucję klucza od {}", sender.id);
            return;
        }
        WireMessage forwarded = WireMessage.of(MessageType.KEY_DISTRIBUTION);
        forwarded.room = sender.room;
        forwarded.epoch = epoch;
        forwarded.clientId = sender.id.toString();
        forwarded.targetId = target.toString();
        forwarded.wrapNonce = message.wrapNonce;
        forwarded.wrappedKey = message.wrappedKey;
        try {
            recipient.send(forwarded);
        } catch (IOException exception) {
            log.debug("Nie można przekazać klucza do {}", recipient.id);
            closeSocket(recipient.socket);
        }
    }

    private void publishMembershipChange(RoomRegistry.Change change) {
        publishMembershipChange(change, null, null);
    }

    private static boolean validEnvelope(WireMessage message) {
        if (message.wrapNonce == null || message.wrappedKey == null
                || message.wrapNonce.length() > 32 || message.wrappedKey.length() > 128) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(message.wrapNonce).length
                    == Protocol.GCM_NONCE_BYTES
                    && Base64.getDecoder().decode(message.wrappedKey).length
                    == Protocol.WRAPPED_KEY_BYTES;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void publishMembershipChange(RoomRegistry.Change change, ClientSession joining,
                                         WireMessage welcome) {
        change.publish(() -> {
            if (joining != null) {
                try {
                    // A new participant must receive WELCOME before any room notifications.
                    joining.send(welcome);
                } catch (IOException exception) {
                    closeSocket(joining.socket);
                }
            }
            broadcast(change, roomMessage(MessageType.ROOM_STATE, change));
            broadcast(change, roomMessage(MessageType.REKEY_REQUEST, change));
        });
    }

    private void broadcast(RoomRegistry.Change change, WireMessage message) {
        for (ClientSession recipient : change.recipients()) {
            try {
                recipient.send(message);
            } catch (IOException exception) {
                log.debug("Nie udało się wysłać wiadomości do {}", recipient.id);
                closeSocket(recipient.socket);
            }
        }
    }

    private static WireMessage roomMessage(MessageType type, RoomRegistry.Change change) {
        WireMessage message = WireMessage.of(type);
        message.room = change.room();
        message.epoch = change.epoch();
        message.leaderId = change.leaderId() == null ? null : change.leaderId().toString();
        message.members = change.members();
        return message;
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException exception) {
            log.debug("Błąd zamykania połączenia TCP", exception);
        }
    }

}

