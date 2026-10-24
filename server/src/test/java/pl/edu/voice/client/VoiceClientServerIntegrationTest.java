package pl.edu.voice.client;

import org.junit.jupiter.api.Test;
import pl.edu.voice.server.TestVoiceServer;

import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
