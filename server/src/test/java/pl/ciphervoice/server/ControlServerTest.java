package pl.ciphervoice.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pl.ciphervoice.common.MessageType;
import pl.ciphervoice.common.Protocol;
import pl.ciphervoice.common.WireMessage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ControlServerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ignoresStaleEnvelopesAndDepartedRecipientsWithoutClosingTheLeader() throws Exception {
        RoomRegistry rooms = new RoomRegistry();
        StringWriter recipientOutput = new StringWriter();
        ClientSession leader = session(1, new StringWriter());
        ClientSession recipient = session(2, recipientOutput);
        rooms.join(leader, "pokoj", "Ala", publicKey());
        rooms.join(recipient, "pokoj", "Ola", publicKey());
        ControlServer server = server(rooms);
        try {
            assertDoesNotThrow(() -> server.forwardKey(leader, envelope(recipient, 1)));
            assertEquals("", recipientOutput.toString());
            rooms.leave(recipient);
            assertDoesNotThrow(() -> server.forwardKey(leader, envelope(recipient, 2)));
            assertEquals("", recipientOutput.toString());
            assertFalse(leader.socket.isClosed());
            assertSame(leader, rooms.find(leader.id));
        } finally {
            server.stop();
        }
    }

    @Test
    void recipientWriteFailureClosesOnlyTheRecipient() throws Exception {
        RoomRegistry rooms = new RoomRegistry();
        ClientSession leader = session(1, new StringWriter());
        ClientSession recipient = session(2, new Writer() {
            @Override public void write(char[] chars, int offset, int length) throws IOException {
                throw new IOException("recipient disconnected");
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
        rooms.join(leader, "pokoj", "Ala", publicKey());
        rooms.join(recipient, "pokoj", "Ola", publicKey());
        ControlServer server = server(rooms);
        try {
            assertDoesNotThrow(() -> server.forwardKey(leader, envelope(recipient, 2)));
            assertTrue(recipient.socket.isClosed());
            assertFalse(leader.socket.isClosed());
        } finally {
            server.stop();
        }
    }

    @Test
    void forwardsOnlyFromTheCurrentLeaderAndReplacesSenderMetadata() throws Exception {
        RoomRegistry rooms = new RoomRegistry();
        StringWriter leaderOutput = new StringWriter();
        StringWriter recipientOutput = new StringWriter();
        ClientSession leader = session(1, leaderOutput);
        ClientSession recipient = session(2, recipientOutput);
        rooms.join(leader, "pokoj", "Ala", publicKey());
        rooms.join(recipient, "pokoj", "Ola", publicKey());
        ControlServer server = server(rooms);
        try {
            server.forwardKey(recipient, envelope(leader, 2));
            assertEquals("", leaderOutput.toString());
            WireMessage message = envelope(recipient, 2);
            message.clientId = UUID.randomUUID().toString();
            message.room = "inny";
            server.forwardKey(leader, message);
            WireMessage forwarded = mapper.readValue(recipientOutput.toString(), WireMessage.class);
            assertEquals(leader.id.toString(), forwarded.clientId);
            assertEquals("pokoj", forwarded.room);
            assertEquals(message.wrapNonce, forwarded.wrapNonce);
            assertEquals(message.wrappedKey, forwarded.wrappedKey);
        } finally {
            server.stop();
        }
    }

    @Test
    void failsStartupSynchronouslyWhenTcpPortIsOccupied() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            ControlServer server = new ControlServer(
                    new VoiceServerProperties(occupied.getLocalPort(), 0), new RoomRegistry(), mapper);
            try {
                assertThrows(BindException.class, server::start);
            } finally {
                server.stop();
            }
        }
    }

    @Test
    void stopsJoinedAndPreJoinConnectionsWithoutWaitingForTheirInput() throws Exception {
        RoomRegistry rooms = new RoomRegistry();
        ControlServer server = server(rooms);
        server.start();
        try (Socket idle = new Socket(InetAddress.getLoopbackAddress(), server.localPort());
             Socket joined = new Socket(InetAddress.getLoopbackAddress(), server.localPort())) {
            joined.setSoTimeout(2_000);
            idle.setSoTimeout(2_000);
            joined.getOutputStream().write((mapper.writeValueAsString(join()) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            joined.getOutputStream().flush();
            BufferedReader responses = new BufferedReader(new InputStreamReader(
                    joined.getInputStream(), StandardCharsets.UTF_8));
            assertEquals(MessageType.WELCOME, mapper.readValue(responses.readLine(), WireMessage.class).type);
            assertEquals(MessageType.ROOM_STATE, mapper.readValue(responses.readLine(), WireMessage.class).type);
            assertEquals(MessageType.REKEY_REQUEST, mapper.readValue(responses.readLine(), WireMessage.class).type);
            assertEquals(1, rooms.participantCount());

            assertTimeoutPreemptively(Duration.ofSeconds(3), server::stop);

            assertEquals(-1, idle.getInputStream().read());
            assertEquals(-1, responses.read());
            assertEquals(0, rooms.participantCount());
        } finally {
            server.stop();
        }
    }

    @Test
    void appliesJoinInactivityTimeoutAndClosesAnUnresponsiveConnection() throws Exception {
        RoomRegistry rooms = new RoomRegistry();
        MemorySocket socket = new MemorySocket(new InputStream() {
            @Override public int read() throws IOException {
                throw new SocketTimeoutException("JOIN timeout");
            }
        });
        ControlServer server = server(rooms);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> server.handle(socket));
            assertEquals(List.of(ControlServer.JOIN_TIMEOUT_MILLIS), socket.timeouts);
            assertTrue(socket.isClosed());
            assertEquals(0, rooms.participantCount());
            assertEquals(MessageType.ERROR, mapper.readValue(socket.output.toByteArray(), WireMessage.class).type);
        } finally {
            server.stop();
        }
    }

    @Test
    void failureWhileResettingJoinTimeoutCannotLeaveAnUnpublishedMembershipChange() throws Exception {
        RoomRegistry rooms = new RoomRegistry();
        MemorySocket socket = new MemorySocket(new ByteArrayInputStream(
                (mapper.writeValueAsString(join()) + "\n").getBytes(StandardCharsets.UTF_8))) {
            @Override public synchronized void setSoTimeout(int timeout) throws SocketException {
                super.setSoTimeout(timeout);
                if (timeout == 0) {
                    throw new SocketException("connection closed while resetting timeout");
                }
            }
        };
        ControlServer server = server(rooms);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> server.handle(socket));
            assertEquals(0, rooms.roomCount());
            assertEquals(0, rooms.participantCount());
            assertTrue(socket.isClosed());
        } finally {
            server.stop();
        }
    }

    @Test
    void rejectsOversizedJoinBeforeParsingOrCreatingMembership() throws Exception {
        RoomRegistry rooms = new RoomRegistry();
        MemorySocket socket = new MemorySocket(new ByteArrayInputStream(
                "x".repeat(Protocol.MAX_CONTROL_FRAME_BYTES + 1).getBytes(StandardCharsets.UTF_8)));
        ControlServer server = server(rooms);
        try {
            server.handle(socket);
            assertEquals(0, rooms.roomCount());
            assertTrue(socket.isClosed());
            assertEquals(MessageType.ERROR, mapper.readValue(socket.output.toByteArray(), WireMessage.class).type);
        } finally {
            server.stop();
        }
    }

    private ControlServer server(RoomRegistry rooms) {
        return new ControlServer(new VoiceServerProperties(0, 0), rooms, mapper);
    }

    private ClientSession session(int tag, Writer writer) {
        return new ClientSession(UUID.randomUUID(), tag, new byte[16], new Socket(),
                new BufferedWriter(writer), mapper);
    }

    private static WireMessage envelope(ClientSession recipient, int epoch) {
        WireMessage message = WireMessage.of(MessageType.KEY_DISTRIBUTION);
        message.targetId = recipient.id.toString();
        message.epoch = epoch;
        message.wrapNonce = Base64.getEncoder().encodeToString(new byte[12]);
        message.wrappedKey = Base64.getEncoder().encodeToString(new byte[48]);
        return message;
    }

    private static WireMessage join() throws Exception {
        WireMessage join = WireMessage.of(MessageType.JOIN);
        join.room = "pokoj";
        join.nickname = "Ala";
        join.publicKey = publicKey();
        return join;
    }

    private static String publicKey() throws Exception {
        return Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic().getEncoded());
    }

    private static class MemorySocket extends Socket {
        private final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final List<Integer> timeouts = new ArrayList<>();

        MemorySocket(InputStream input) {
            this.input = input;
        }

        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public synchronized void setSoTimeout(int timeout) throws SocketException {
            timeouts.add(timeout);
        }
    }
}
