package pl.edu.voice.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.edu.voice.common.JsonLines;
import pl.edu.voice.common.MessageType;
import pl.edu.voice.common.Protocol;
import pl.edu.voice.common.WireMessage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ControlServer {
    private static final Logger log = LoggerFactory.getLogger(ControlServer.class);

    private final VoiceServerProperties properties;
    private final RoomRegistry rooms;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger senderTags = new AtomicInteger(1000);
    private final SecureRandom random = new SecureRandom();
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile ServerSocket serverSocket;

    public ControlServer(VoiceServerProperties properties, RoomRegistry rooms, ObjectMapper mapper) {
        this.properties = properties;
        this.rooms = rooms;
        this.mapper = mapper;
    }

    @PostConstruct
    void start() {
        executor.submit(this::acceptLoop);
    }

    @PreDestroy
    void stop() {
        stopped.set(true);
        closeQuietly(serverSocket);
        serverSocket = null;
        for (ClientSession session : rooms.allSessions()) {
            session.closeSocket();
        }
        for (Socket socket : openSockets) {
            closeQuietly(socket);
        }
        openSockets.clear();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Nie wszystkie wątki serwera TCP zakończyły się w ciągu 5 sekund");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop() {
        try (ServerSocket listener = new ServerSocket()) {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(properties.controlPort()));
            serverSocket = listener;
            if (stopped.get()) {
                return;
            }
            log.info("Kanał sterujący TCP działa na porcie {}", properties.controlPort());
            while (!stopped.get()) {
                Socket socket = listener.accept();
                openSockets.add(socket);
                try {
                    socket.setTcpNoDelay(true);
                    socket.setKeepAlive(true);
                    executor.submit(() -> {
                        try {
                            handle(socket);
                        } finally {
                            openSockets.remove(socket);
                        }
                    });
                } catch (IOException | RejectedExecutionException exception) {
                    openSockets.remove(socket);
                    closeQuietly(socket);
                }
            }
        } catch (IOException exception) {
            if (!stopped.get()) {
                log.error("Błąd serwera TCP", exception);
            }
        } finally {
            serverSocket = null;
        }
    }

    private void handle(Socket socket) {
        ClientSession session = null;
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8))) {
            try {
                String firstLine = JsonLines.read(reader, Protocol.MAX_CONTROL_LINE_CHARS);
                if (firstLine == null) {
                    return;
                }
                WireMessage join = parseMessage(firstLine);
                if (join.type != MessageType.JOIN) {
                    throw new IllegalArgumentException("Pierwszą wiadomością musi być JOIN");
                }

                byte[] relayToken = new byte[Protocol.RELAY_TOKEN_BYTES];
                random.nextBytes(relayToken);
                session = new ClientSession(UUID.randomUUID(), senderTags.incrementAndGet(),
                        relayToken, socket, writer, mapper);
                RoomRegistry.Change change = rooms.join(
                        session, join.room, join.nickname, join.publicKey);

                WireMessage welcome = roomMessage(MessageType.WELCOME, change);
                welcome.clientId = session.id.toString();
                welcome.senderTag = session.senderTag;
                welcome.relayToken = Base64.getEncoder().encodeToString(session.relayToken);
                session.send(welcome);
                publishMembershipChange(change);
                log.info("{} dołączył do pokoju {}", session.nickname, session.room);

                String line;
                while (!stopped.get()
                        && (line = JsonLines.read(reader, Protocol.MAX_CONTROL_LINE_CHARS)) != null) {
                    WireMessage message;
                    try {
                        message = parseMessage(line);
                    } catch (IOException exception) {
                        log.debug("Nieprawidłowy komunikat od {}: {}",
                                session.id, exception.getMessage());
                        continue;
                    }
                    if (message.type == MessageType.KEY_DISTRIBUTION) {
                        forwardKey(session, message);
                    }
                }
            } catch (Exception exception) {
                if (session == null || session.room == null) {
                    sendError(writer, exception);
                } else {
                    log.debug("Rozłączono klienta {}: {}", session.id, exception.getMessage());
                }
            }
        } catch (Exception exception) {
            log.debug("Błąd połączenia sterującego: {}", exception.getMessage());
        } finally {
            if (session != null && session.room != null) {
                RoomRegistry.Change change = rooms.leave(session);
                if (change != null && !change.recipients().isEmpty()) {
                    publishMembershipChange(change);
                }
                log.info("{} opuścił pokój {}", session.nickname, session.room);
            }
        }
    }

    private WireMessage parseMessage(String line) throws IOException {
        try {
            return mapper.readValue(line, WireMessage.class);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Nieprawidłowy JSON", exception);
        }
    }

    private void sendError(BufferedWriter writer, Exception exception) {
        try {
            WireMessage error = WireMessage.of(MessageType.ERROR);
            error.error = exception.getMessage() == null
                    ? "Nie można dołączyć" : exception.getMessage();
            JsonLines.write(writer, mapper.writeValueAsString(error));
        } catch (IOException ignored) {
            // The client may already have closed the connection.
        }
    }

    /**
     * Relays an end-to-end encrypted key envelope. A stale envelope (for
     * example when somebody joined while the leader was distributing keys) is
     * silently dropped instead of disconnecting the leader.
     */
    private void forwardKey(ClientSession sender, WireMessage message) {
        try {
            if (message.targetId == null || message.epoch == null
                    || message.wrapNonce == null || message.wrappedKey == null) {
                return;
            }
            UUID target = UUID.fromString(message.targetId);
            int epoch = message.epoch;
            if (!rooms.mayDistribute(sender, target, epoch) || !validEnvelope(message)) {
                log.debug("Odrzucono nieaktualną lub nieprawidłową kopertę klucza od {}",
                        sender.id);
                return;
            }
            ClientSession recipient = rooms.findInRoom(sender.room, target);
            if (recipient == null) {
                return;
            }
            WireMessage forwarded = WireMessage.of(MessageType.KEY_DISTRIBUTION);
            forwarded.room = sender.room;
            forwarded.epoch = epoch;
            forwarded.clientId = sender.id.toString();
            forwarded.targetId = target.toString();
            forwarded.wrapNonce = message.wrapNonce;
            forwarded.wrappedKey = message.wrappedKey;
            recipient.send(forwarded);
        } catch (Exception exception) {
            log.debug("Odrzucono kopertę klucza od {}: {}",
                    sender.id, exception.getMessage());
        }
    }

    private static boolean validEnvelope(WireMessage message) {
        if (message.wrapNonce.length() > 32 || message.wrappedKey.length() > 128) {
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

    private void publishMembershipChange(RoomRegistry.Change change) {
        if (!rooms.isCurrentRoomEpoch(change.room(), change.epoch())) {
            return; // a newer membership change already superseded this one
        }
        WireMessage state = roomMessage(MessageType.ROOM_STATE, change);
        broadcast(change, state);
        WireMessage rekey = roomMessage(MessageType.REKEY_REQUEST, change);
        broadcast(change, rekey);
    }

    private void broadcast(RoomRegistry.Change change, WireMessage message) {
        for (ClientSession recipient : change.recipients()) {
            try {
                recipient.send(message);
            } catch (IOException exception) {
                log.debug("Nie udało się wysłać wiadomości do {}", recipient.id);
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

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }
}
