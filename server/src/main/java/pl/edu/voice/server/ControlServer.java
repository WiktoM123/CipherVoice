package pl.edu.voice.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.edu.voice.common.MessageType;
import pl.edu.voice.common.Protocol;
import pl.edu.voice.common.WireMessage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    void stop() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
        }
        executor.close();
    }

    private void acceptLoop() {
        try (ServerSocket listener = new ServerSocket(properties.controlPort())) {
            serverSocket = listener;
            log.info("Kanał sterujący TCP działa na porcie {}", properties.controlPort());
            while (!listener.isClosed()) {
                Socket socket = listener.accept();
                socket.setTcpNoDelay(true);
                executor.submit(() -> handle(socket));
            }
        } catch (IOException exception) {
            if (serverSocket == null || !serverSocket.isClosed()) {
                log.error("Błąd serwera TCP", exception);
            }
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
                String firstLine = reader.readLine();
                if (firstLine == null) {
                    return;
                }
                WireMessage join = mapper.readValue(firstLine, WireMessage.class);
                if (join.type != MessageType.JOIN) {
                    throw new IllegalArgumentException("Pierwszą wiadomością musi być JOIN");
                }

                byte[] relayToken = new byte[Protocol.RELAY_TOKEN_BYTES];
                random.nextBytes(relayToken);
                session = new ClientSession(UUID.randomUUID(), senderTags.incrementAndGet(), relayToken,
                        socket, writer, mapper);
                RoomRegistry.Change change = rooms.join(session, join.room, join.nickname, join.publicKey);

                WireMessage welcome = roomMessage(MessageType.WELCOME, change);
                welcome.clientId = session.id.toString();
                welcome.senderTag = session.senderTag;
                welcome.relayToken = Base64.getEncoder().encodeToString(session.relayToken);
                session.send(welcome);
                publishMembershipChange(change);
                log.info("{} dołączył do pokoju {}", session.nickname, session.room);

                String line;
                while ((line = reader.readLine()) != null) {
                    WireMessage message = mapper.readValue(line, WireMessage.class);
                    if (message.type == MessageType.KEY_DISTRIBUTION) {
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
            if (session != null) {
                RoomRegistry.Change change = rooms.leave(session);
                if (change != null && !change.recipients().isEmpty()) {
                    publishMembershipChange(change);
                }
                log.info("{} opuścił pokój {}", session.nickname, session.room);
            }
        }
    }

    private void forwardKey(ClientSession sender, WireMessage message) throws IOException {
        UUID target = UUID.fromString(message.targetId);
        int epoch = message.epoch == null ? -1 : message.epoch;
        if (!rooms.mayDistribute(sender, target, epoch)) {
            throw new IllegalArgumentException("Odrzucono nieuprawnioną dystrybucję klucza");
        }
        ClientSession recipient = rooms.findInRoom(sender.room, target);
        WireMessage forwarded = WireMessage.of(MessageType.KEY_DISTRIBUTION);
        forwarded.room = sender.room;
        forwarded.epoch = epoch;
        forwarded.clientId = sender.id.toString();
        forwarded.targetId = target.toString();
        forwarded.wrapNonce = message.wrapNonce;
        forwarded.wrappedKey = message.wrappedKey;
        recipient.send(forwarded);
    }

    private void publishMembershipChange(RoomRegistry.Change change) {
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

}

