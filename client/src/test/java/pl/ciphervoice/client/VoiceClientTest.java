package pl.ciphervoice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import pl.ciphervoice.common.MemberInfo;
import pl.ciphervoice.common.MessageType;
import pl.ciphervoice.common.Protocol;
import pl.ciphervoice.common.WireMessage;

import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceClientTest {
    @Test
    void duplicateKeyAndOldEpochMessagesCannotResetTheActiveSender() throws Exception {
        try (Session session = new Session(false, false)) {
            session.start();
            SecretKeySpec firstKey = CryptoSupport.newGroupKey();
            WireMessage firstDistribution = session.distribution(1, firstKey);
            session.send(firstDistribution);
            session.awaitEncryption(1);
            assertEquals(0, session.sendAudio(firstKey).sequence());

            session.send(firstDistribution);
            session.checkpoint(1);
            assertEquals(1, session.sendAudio(firstKey).sequence());

            session.checkpoint(2);
            SecretKeySpec secondKey = CryptoSupport.newGroupKey();
            session.send(session.distribution(2, secondKey));
            session.awaitEncryption(2);
            assertEquals(0, session.sendAudio(secondKey).sequence());

            session.send(session.roomMessage(MessageType.ROOM_STATE, 1));
            session.send(session.roomMessage(MessageType.REKEY_REQUEST, 1));
            session.send(firstDistribution);
            session.checkpoint(2);
            AudioPacketCodec.DecodedPacket packet = session.sendAudio(secondKey);
            assertEquals(2, packet.epoch());
            assertEquals(1, packet.sequence());
        }
    }

    @Test
    void duplicateRekeyRequestDoesNotRotateAnAlreadyActiveLeaderEpoch() throws Exception {
        try (Session session = new Session(true, false)) {
            session.start();
            session.send(session.roomMessage(MessageType.REKEY_REQUEST, 1));
            session.awaitEncryption(1);
            assertEquals(0, sequence(session.sendAudio()));

            session.send(session.roomMessage(MessageType.REKEY_REQUEST, 1));
            session.checkpoint(1);
            assertEquals(1, sequence(session.sendAudio()));
        }
    }

    @Test
    void controlEofClosesAudioAndBothSocketsExactlyOnce() throws Exception {
        try (Session session = new Session(false, false)) {
            session.start();
            session.server.close();
            assertTrue(session.disconnected.await(5, TimeUnit.SECONDS));
            assertFalse(session.client.isRunning());
            session.client.close();

            verify(session.tcp.constructed().getFirst()).close();
            verify(session.udp.constructed().getFirst()).close();
            verify(session.audio.constructed().getFirst()).close();
            assertEquals(1, session.disconnectCount.get());
        }
    }

    @Test
    void udpInitializationFailureClosesResourcesWithoutRelyingOnTheGui() throws Exception {
        try (Session session = new Session(false, true)) {
            assertThrows(SocketException.class, session::start);
            assertFalse(session.client.isRunning());
            session.client.close();

            verify(session.tcp.constructed().getFirst()).close();
            verify(session.udp.constructed().getFirst()).close();
            assertTrue(session.audio.constructed().isEmpty());
            assertEquals(1, session.disconnectCount.get());
        }
    }

    @Test
    void tcpInitializationFailureClosesTheSocketWithoutRelyingOnTheGui() throws Exception {
        VoiceClientListener listener = mock(VoiceClientListener.class);
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (socket, context) ->
                doThrow(new IOException("connect failed")).when(socket)
                        .connect(any(SocketAddress.class), anyInt()))) {
            VoiceClient client = new VoiceClient("localhost", 8090, 8091,
                    "room", "name", listener);
            assertThrows(IOException.class, client::start);
            client.close();

            verify(sockets.constructed().getFirst()).close();
            assertFalse(client.isRunning());
            verify(listener).onDisconnected(any(String.class));
        }
    }

    @Test
    void unsuccessfulUdpRegistrationStillWaitsBeforeEveryRetry() {
        List<String> events = new ArrayList<>();
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();

        VoiceClient.retryRegistration(active::get, () -> {
            events.add("attempt");
            attempts.incrementAndGet();
            throw new IOException("UDP unavailable");
        }, () -> {
            events.add("wait");
            if (attempts.get() == 3) {
                active.set(false);
            }
        });

        assertEquals(List.of("attempt", "wait", "attempt", "wait", "attempt", "wait"), events);
    }

    @Test
    void interruptedRegistrationStopsRetryingAndPreservesTheInterrupt() {
        AtomicInteger attempts = new AtomicInteger();
        try {
            VoiceClient.retryRegistration(() -> true, attempts::incrementAndGet,
                    () -> { throw new InterruptedException(); });
            assertEquals(1, attempts.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static long sequence(byte[] packet) {
        return ByteBuffer.wrap(packet).getLong(Integer.BYTES + 1 + 2 * Long.BYTES
                + 2 * Integer.BYTES);
    }

    /** Entirely mocked transports: no TCP listener, UDP traffic, GUI or audio devices. */
    private static final class Session implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final UUID self = UUID.randomUUID();
        private final UUID leader;
        private final KeyPair leaderIdentity = CryptoSupport.newIdentityKeyPair();
        private final PipedInputStream input = new PipedInputStream(16_384);
        private final BufferedWriter server = new BufferedWriter(new OutputStreamWriter(
                new PipedOutputStream(input), StandardCharsets.UTF_8));
        private final ByteArrayOutputStream controlOutput = new ByteArrayOutputStream();
        private final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
        private final BlockingQueue<byte[]> outgoing = new LinkedBlockingQueue<>();
        private final BlockingQueue<Integer> roomEvents = new LinkedBlockingQueue<>();
        private final BlockingQueue<EncryptionEvent> encryptionEvents = new LinkedBlockingQueue<>();
        private final AtomicReference<Consumer<byte[]>> audioSender = new AtomicReference<>();
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private final AtomicInteger disconnectCount = new AtomicInteger();
        private final MockedConstruction<Socket> tcp;
        private final MockedConstruction<DatagramSocket> udp;
        private final MockedConstruction<OpusVoiceEngine> audio;
        private final VoiceClient client;

        @SuppressWarnings("unchecked")
        Session(boolean isLeader, boolean failUdp) throws Exception {
            leader = isLeader ? self : UUID.randomUUID();
            tcp = mockConstruction(Socket.class, (socket, context) -> {
                when(socket.getInputStream()).thenReturn(input);
                when(socket.getOutputStream()).thenReturn(controlOutput);
                doAnswer(invocation -> { input.close(); return null; }).when(socket).close();
            });
            udp = mockConstruction(DatagramSocket.class, (socket, context) -> {
                if (failUdp) {
                    doThrow(new SocketException("UDP initialization failed"))
                            .when(socket).connect(any(SocketAddress.class));
                }
                doAnswer(invocation -> {
                    DatagramPacket packet = invocation.getArgument(0);
                    byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(),
                            packet.getOffset() + packet.getLength());
                    if (ByteBuffer.wrap(data).getInt() == Protocol.AUDIO_MAGIC) {
                        outgoing.add(data);
                    }
                    return null;
                }).when(socket).send(any(DatagramPacket.class));
                doAnswer(invocation -> {
                    byte[] data = incoming.take();
                    if (data.length == 0) {
                        throw new SocketException("closed");
                    }
                    DatagramPacket packet = invocation.getArgument(0);
                    System.arraycopy(data, 0, packet.getData(), packet.getOffset(), data.length);
                    packet.setLength(data.length);
                    return null;
                }).when(socket).receive(any(DatagramPacket.class));
                doAnswer(invocation -> { incoming.add(new byte[0]); return null; })
                        .when(socket).close();
            });
            audio = mockConstruction(OpusVoiceEngine.class, (engine, context) ->
                    audioSender.set((Consumer<byte[]>) context.arguments().getFirst()));
            client = new VoiceClient("localhost", 8090, 8091, "room", "name",
                    new VoiceClientListener() {
                        @Override
                        public void onRoomState(List<MemberInfo> members, UUID selfId,
                                                UUID leaderId, int epoch) {
                            roomEvents.add(epoch);
                        }

                        @Override
                        public void onEncryptionChanged(boolean ready, int epoch) {
                            encryptionEvents.add(new EncryptionEvent(ready, epoch));
                        }

                        @Override
                        public void onDisconnected(String reason) {
                            disconnectCount.incrementAndGet();
                            disconnected.countDown();
                        }
                    });
        }

        void start() throws Exception {
            WireMessage welcome = roomMessage(MessageType.WELCOME, 1);
            welcome.clientId = self.toString();
            welcome.senderTag = 42;
            welcome.relayToken = Base64.getEncoder().encodeToString(new byte[Protocol.RELAY_TOKEN_BYTES]);
            send(welcome);
            client.start();
            assertEquals(Integer.valueOf(1), roomEvents.poll(5, TimeUnit.SECONDS));
        }

        WireMessage roomMessage(MessageType type, int epoch) {
            WireMessage message = WireMessage.of(type);
            message.epoch = epoch;
            message.leaderId = leader.toString();
            message.members = new ArrayList<>(List.of(new MemberInfo(self.toString(), "name", "", 42)));
            if (!leader.equals(self)) {
                message.members.add(new MemberInfo(leader.toString(), "leader",
                        CryptoSupport.encodePublicKey(leaderIdentity.getPublic()), 43));
            }
            return message;
        }

        WireMessage distribution(int epoch, SecretKeySpec key) throws Exception {
            WireMessage join = mapper.readValue(controlOutput.toString(StandardCharsets.UTF_8), WireMessage.class);
            CryptoSupport.WrappedKey wrapped = CryptoSupport.wrapGroupKey(key,
                    leaderIdentity.getPrivate(), CryptoSupport.decodePublicKey(join.publicKey),
                    "voice-key/v" + Protocol.VERSION + "/room/" + epoch + "/" + leader + "/" + self);
            WireMessage message = WireMessage.of(MessageType.KEY_DISTRIBUTION);
            message.epoch = epoch;
            message.clientId = leader.toString();
            message.targetId = self.toString();
            message.wrapNonce = wrapped.nonce();
            message.wrappedKey = wrapped.ciphertext();
            return message;
        }

        void send(WireMessage message) throws IOException {
            server.write(mapper.writeValueAsString(message));
            server.newLine();
            server.flush();
        }

        void checkpoint(int epoch) throws Exception {
            roomEvents.clear();
            send(roomMessage(MessageType.ROOM_STATE, epoch));
            assertEquals(Integer.valueOf(epoch), roomEvents.poll(5, TimeUnit.SECONDS));
        }

        void awaitEncryption(int epoch) throws Exception {
            EncryptionEvent event;
            do {
                event = encryptionEvents.poll(5, TimeUnit.SECONDS);
                assertNotNull(event, "Client did not activate encryption");
            } while (!event.ready() || event.epoch() != epoch);
        }

        byte[] sendAudio() throws Exception {
            audioSender.get().accept(new byte[]{1, 2, 3});
            byte[] packet = outgoing.poll(5, TimeUnit.SECONDS);
            assertNotNull(packet, "Client did not send audio");
            return packet;
        }

        AudioPacketCodec.DecodedPacket sendAudio(SecretKeySpec key) throws Exception {
            return AudioPacketCodec.decrypt(sendAudio(), key);
        }

        @Override
        public void close() throws Exception {
            client.close();
            server.close();
            input.close();
            audio.close();
            udp.close();
            tcp.close();
        }

        private record EncryptionEvent(boolean ready, int epoch) {
        }
    }
}
