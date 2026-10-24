package pl.edu.voice.client;

import org.junit.jupiter.api.Test;
import pl.edu.voice.common.MessageType;
import pl.edu.voice.common.Protocol;
import pl.edu.voice.common.WireMessage;
import pl.edu.voice.server.TestVoiceServer;

import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Starts the real server components and two headless clients. Verifies that the
 * leader distributes the same group key to a new member and that every
 * membership change produces a fresh key.
 */
class VoiceClientServerIntegrationTest {
    @Test
    void clientsAgreeOnGroupKeyAndRotateItAfterMemberLeaves() throws Exception {
        int controlPort = freeTcpPort();
        int udpPort = freeUdpPort(controlPort);

        try (TestVoiceServer server = new TestVoiceServer(controlPort, udpPort)) {
            server.start();

            Recorder aliceEvents = new Recorder();
            VoiceClient alice = new VoiceClient("127.0.0.1", controlPort, udpPort,
                    "pokoj", "Alice", aliceEvents, false);
            try {
                alice.start();
                await(() -> aliceEvents.ready && aliceEvents.epoch == 1, 5_000,
                        "Alice nie otrzymała pierwszego klucza");
                byte[] firstKey = groupKey(alice);
                assertNotNull(firstKey);
                assertEquals(32, firstKey.length);

                Recorder bobEvents = new Recorder();
                VoiceClient bob = new VoiceClient("127.0.0.1", controlPort, udpPort,
                        "pokoj", "Bob", bobEvents, false);
                byte[] keyBeforeLeaving;
                try {
                    bob.start();
                    await(() -> aliceEvents.ready && aliceEvents.epoch == 2, 5_000,
                            "Alice nie przeszła do epoki 2");
                    await(() -> bobEvents.ready && bobEvents.epoch == 2, 5_000,
                            "Bob nie otrzymał klucza epoki 2");

                    byte[] aliceKey = groupKey(alice);
                    byte[] bobKey = groupKey(bob);
                    keyBeforeLeaving = aliceKey;
                    assertNotNull(aliceKey);
                    assertNotNull(bobKey);
                    assertArrayEquals(aliceKey, bobKey,
                            "Obaj uczestnicy muszą mieć ten sam klucz grupowy");
                    assertFalse(Arrays.equals(firstKey, aliceKey),
                            "Nowy uczestnik musi wymusić nowy klucz");
                    assertNull(aliceEvents.error);
                    assertNull(bobEvents.error);
                } finally {
                    bob.close();
                }

                await(() -> aliceEvents.ready && aliceEvents.epoch >= 3, 5_000,
                        "Alice nie przeszła do epoki 3 po wyjściu Boba");
                byte[] rotatedKey = groupKey(alice);
                assertNotNull(rotatedKey);
                assertFalse(Arrays.equals(keyBeforeLeaving, rotatedKey),
                        "Klucz powinien zostać zmieniony po wyjściu uczestnika");
                assertNull(aliceEvents.error);
            } finally {
                alice.close();
            }
        }
    }

    @Test
    void concurrentJoinsConvergeOnOneKey() throws Exception {
        int controlPort = freeTcpPort();
        int udpPort = freeUdpPort(controlPort);
        int clientCount = 5;

        try (TestVoiceServer server = new TestVoiceServer(controlPort, udpPort)) {
            server.start();

            List<VoiceClient> clients = new ArrayList<>();
            List<Recorder> recorders = new ArrayList<>();
            try {
                for (int i = 0; i < clientCount; i++) {
                    Recorder recorder = new Recorder();
                    recorders.add(recorder);
                    clients.add(new VoiceClient("127.0.0.1", controlPort, udpPort,
                            "pokoj", "User" + i, recorder, false));
                }

                AtomicReference<Throwable> failure = new AtomicReference<>();
                List<Thread> starts = new ArrayList<>();
                for (VoiceClient client : clients) {
                    starts.add(Thread.ofVirtual().start(() -> {
                        try {
                            client.start();
                        } catch (Throwable throwable) {
                            failure.compareAndSet(null, throwable);
                        }
                    }));
                }
                for (Thread start : starts) {
                    start.join(5_000);
                }
                if (failure.get() != null) {
                    fail("Klient nie dołączył: " + failure.get(), failure.get());
                }

                await(() -> recorders.stream()
                                .allMatch(recorder -> recorder.ready
                                        && recorder.epoch == clientCount),
                        10_000, "Klienci nie uzgodnili klucza końcowej epoki");

                byte[] expected = groupKey(clients.get(0));
                assertNotNull(expected);
                for (int i = 1; i < clients.size(); i++) {
                    assertArrayEquals(expected, groupKey(clients.get(i)),
                            "Klient " + i + " ma inny klucz grupowy");
                }
                for (int i = 0; i < recorders.size(); i++) {
                    assertNull(recorders.get(i).error,
                            "Klient " + i + " zgłosił błąd");
                }
            } finally {
                for (VoiceClient client : clients) {
                    client.close();
                }
            }
        }
    }

    @Test
    void clientsExchangeRandomNoncePacketsAndRejectNonceTampering() throws Exception {
        int controlPort = freeTcpPort();
        int udpPort = freeUdpPort(controlPort);
        try (TestVoiceServer server = new TestVoiceServer(controlPort, udpPort)) {
            server.start();
            Recorder aliceEvents = new Recorder();
            Recorder bobEvents = new Recorder();
            try (VoiceClient alice = new VoiceClient("127.0.0.1", controlPort, udpPort,
                         "pokoj", "Alice", aliceEvents, false);
                 VoiceClient bob = new VoiceClient("127.0.0.1", controlPort, udpPort,
                         "pokoj", "Bob", bobEvents, false)) {
                alice.start();
                bob.start();
                await(() -> aliceEvents.ready && aliceEvents.epoch == 2
                        && bobEvents.ready && bobEvents.epoch == 2, 5_000, "Missing group key");
                await(() -> udpRegistered(server, alice) && udpRegistered(server, bob),
                        5_000, "UDP registration did not complete");
                UUID aliceId = (UUID) readField(alice, "clientId");
                sendAudio(alice, new byte[]{1, 2, 3});
                await(() -> receivedSequence(bob, aliceId) == 0, 3_000, "First packet missing");
                sendAudio(alice, new byte[]{4, 5, 6});
                await(() -> receivedSequence(bob, aliceId) == 1, 3_000, "Second packet missing");

                byte[] tampered = AudioPacketCodec.encrypt(aliceId, 2,
                        (int) readField(alice, "senderTag"), 1000, new byte[]{7},
                        new SecretKeySpec(groupKey(alice), "AES"));
                tampered[Protocol.AUDIO_HEADER_BYTES - Short.BYTES - Protocol.GCM_NONCE_BYTES] ^= 1;
                ((DatagramSocket) readField(alice, "udpSocket"))
                        .send(new DatagramPacket(tampered, tampered.length));
                sendAudio(alice, new byte[]{8, 9});
                await(() -> receivedSequence(bob, aliceId) == 2, 3_000,
                        "A modified nonce poisoned replay protection or blocked valid audio");
                assertNull(aliceEvents.error);
                assertNull(bobEvents.error);
            }
        }
    }

    @Test
    void randomNonceBudgetSurvivesDuplicateEnvelopesAndResetsOnlyWithANewEpoch() throws Exception {
        int controlPort = freeTcpPort();
        int udpPort = freeUdpPort(controlPort);
        try (TestVoiceServer server = new TestVoiceServer(controlPort, udpPort)) {
            server.start();
            Recorder aliceEvents = new Recorder();
            Recorder bobEvents = new Recorder();
            try (VoiceClient alice = new VoiceClient("127.0.0.1", controlPort, udpPort,
                         "pokoj", "Alice", aliceEvents, false);
                 VoiceClient bob = new VoiceClient("127.0.0.1", controlPort, udpPort,
                         "pokoj", "Bob", bobEvents, false)) {
                alice.start();
                bob.start();
                await(() -> aliceEvents.ready && aliceEvents.epoch == 2
                        && bobEvents.ready && bobEvents.epoch == 2, 5_000, "Missing group key");
                await(() -> udpRegistered(server, alice) && udpRegistered(server, bob),
                        5_000, "UDP registration did not complete");
                Object originalState = readField(bob, "audioKey");
                long limit = (long) readField(originalState, "packetLimit");
                assertEquals(Protocol.MAX_RANDOM_NONCE_ENCRYPTIONS_PER_KEY / 2, limit);
                AtomicLong count = (AtomicLong) readField(originalState, "encryptedPackets");
                count.set(limit - 1);

                UUID aliceId = (UUID) readField(alice, "clientId");
                UUID bobId = (UUID) readField(bob, "clientId");
                KeyPair aliceIdentity = (KeyPair) readField(alice, "identity");
                KeyPair bobIdentity = (KeyPair) readField(bob, "identity");
                CryptoSupport.WrappedKey wrapped = CryptoSupport.wrapGroupKey(
                        new SecretKeySpec(groupKey(bob), "AES"), aliceIdentity.getPrivate(),
                        bobIdentity.getPublic(), "voice-key/v" + Protocol.VERSION + "/pokoj/2/"
                                + aliceId + "/" + bobId);
                WireMessage duplicate = WireMessage.of(MessageType.KEY_DISTRIBUTION);
                duplicate.clientId = aliceId.toString();
                duplicate.targetId = bobId.toString();
                duplicate.epoch = 2;
                duplicate.wrapNonce = wrapped.nonce();
                duplicate.wrappedKey = wrapped.ciphertext();
                Method accept = VoiceClient.class.getDeclaredMethod("tryAcceptKey", WireMessage.class);
                accept.setAccessible(true);
                assertTrue((boolean) accept.invoke(bob, duplicate));
                assertSame(originalState, readField(bob, "audioKey"));
                assertEquals(limit - 1, count.get());

                CryptoSupport.WrappedKey conflict = CryptoSupport.wrapGroupKey(
                        CryptoSupport.newGroupKey(), aliceIdentity.getPrivate(),
                        bobIdentity.getPublic(), "voice-key/v" + Protocol.VERSION + "/pokoj/2/"
                                + aliceId + "/" + bobId);
                duplicate.wrapNonce = conflict.nonce();
                duplicate.wrappedKey = conflict.ciphertext();
                assertFalse((boolean) accept.invoke(bob, duplicate));
                assertSame(originalState, readField(bob, "audioKey"));
                assertEquals(limit - 1, count.get());

                sendAudio(bob, new byte[]{1});
                await(() -> receivedSequence(alice, bobId) == 0, 3_000, "Last allowed packet missing");
                for (int i = 0; i < 3; i++) {
                    sendAudio(bob, new byte[]{2});
                }
                assertNotNull(bobEvents.error);
                assertTrue(bobEvents.error.contains("limit"));
                assertEquals(1, ((AtomicLong) readField(bob, "sequence")).get());
                assertEquals(limit + 1, count.get());

                alice.close();
                await(() -> bobEvents.ready && bobEvents.epoch == 3, 5_000, "Rekey did not complete");
                Object newState = readField(bob, "audioKey");
                assertEquals(Protocol.MAX_RANDOM_NONCE_ENCRYPTIONS_PER_KEY,
                        (long) readField(newState, "packetLimit"));
                assertEquals(0, ((AtomicLong) readField(newState, "encryptedPackets")).get());
                sendAudio(bob, new byte[]{3});
                assertEquals(2, ((AtomicLong) readField(bob, "sequence")).get());
                assertEquals(1, ((AtomicLong) readField(newState, "encryptedPackets")).get());
            }
        }
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean udpRegistered(TestVoiceServer server, VoiceClient client) {
        try {
            Object registry = readField(readField(server, "control"), "rooms");
            Method find = registry.getClass().getDeclaredMethod("find", UUID.class);
            find.setAccessible(true);
            Object session = find.invoke(registry, readField(client, "clientId"));
            return session != null && readField(session, "udpAddress") != null;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long receivedSequence(VoiceClient client, UUID sender) {
        try {
            Map<?, ?> sequences = (Map<?, ?>) readField(client, "receivedSequences");
            Long value = (Long) sequences.get(sender);
            return value == null ? -1 : value;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void sendAudio(VoiceClient client, byte[] opus) throws Exception {
        Method send = VoiceClient.class.getDeclaredMethod("sendAudio", byte[].class);
        send.setAccessible(true);
        send.invoke(client, (Object) opus);
    }

    private static byte[] groupKey(VoiceClient client) throws Exception {
        Field field = VoiceClient.class.getDeclaredField("audioKey");
        field.setAccessible(true);
        Object audioKey = field.get(client);
        if (audioKey == null) {
            return null;
        }
        Method keyMethod = audioKey.getClass().getDeclaredMethod("key");
        keyMethod.setAccessible(true);
        return ((SecretKeySpec) keyMethod.invoke(audioKey)).getEncoded();
    }

    private static void await(BooleanSupplier condition, long timeoutMillis, String message)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail(message);
    }

    private static int freeTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int freeUdpPort(int forbidden) throws Exception {
        for (int i = 0; i < 10; i++) {
            try (DatagramSocket socket = new DatagramSocket(0)) {
                int port = socket.getLocalPort();
                if (port != forbidden) {
                    return port;
                }
            }
        }
        throw new IllegalStateException("Nie udało się znaleźć wolnego portu UDP");
    }

    private static final class Recorder implements VoiceClientListener {
        private volatile boolean ready;
        private volatile int epoch = -1;
        private volatile String error;

        @Override
        public void onEncryptionChanged(boolean ready, int epoch) {
            this.ready = ready;
            this.epoch = epoch;
        }

        @Override
        public void onError(String message) {
            this.error = message;
        }
    }
}
