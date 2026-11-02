package pl.ciphervoice.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RoomRegistryTest {
    @Test
    void joinsAndElectsTheOldestRemainingMember() throws Exception {
        RoomRegistry rooms = new RoomRegistry();
        ClientSession first = session(1);
        ClientSession second = session(2);
        RoomRegistry.Change firstJoin = rooms.join(first, "pokoj", "Ala", publicKey());
        RoomRegistry.Change secondJoin = rooms.join(second, "pokoj", "Ola", publicKey());

        assertEquals(1, firstJoin.epoch());
        assertEquals(2, secondJoin.epoch());
        assertEquals(first.id, secondJoin.leaderId());
        assertEquals(2, rooms.participantCount());
        assertEquals(1, rooms.roomCount());
        assertTrue(rooms.mayDistribute(first, second.id, 2));
        assertFalse(rooms.mayDistribute(second, first.id, 2));

        RoomRegistry.Change departure = rooms.leave(first);
        assertEquals(3, departure.epoch());
        assertEquals(second.id, departure.leaderId());
        assertEquals(List.of(second), List.copyOf(departure.recipients()));
        assertNull(rooms.find(first.id));
        assertTrue(rooms.mayDistribute(second, second.id, 3));

        RoomRegistry.Change lastDeparture = rooms.leave(second);
        assertEquals(4, lastDeparture.epoch());
        assertNull(lastDeparture.leaderId());
        assertTrue(lastDeparture.recipients().isEmpty());
        assertEquals(0, rooms.roomCount());
        assertEquals(0, rooms.participantCount());
        assertNull(rooms.leave(second));
    }

    @Test
    void rejectsDuplicateNicknameWithoutChangingMembership() throws Exception {
        RoomRegistry rooms = new RoomRegistry();
        ClientSession first = session(1);
        ClientSession duplicate = session(2);
        rooms.join(first, "pokoj", "Ala", publicKey());

        assertThrows(IllegalArgumentException.class,
                () -> rooms.join(duplicate, "pokoj", "aLA", publicKey()));

        assertEquals(1, rooms.participantCount());
        assertNull(duplicate.room);
        assertNull(rooms.find(duplicate.id));
        assertTrue(rooms.isCurrentEpoch(first, 1));
    }

    @Test
    void rejectsMalformedAndUnusablePublicKeysBeforeCreatingARoom() throws Exception {
        String otherAlgorithm = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        String lowOrder = Base64.getEncoder().encodeToString(KeyFactory.getInstance("X25519")
                .generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger.ZERO))
                .getEncoded());

        for (String invalid : new String[]{null, "", "%%%", "AA==", "a".repeat(257),
                otherAlgorithm, lowOrder}) {
            RoomRegistry rooms = new RoomRegistry();
            ClientSession client = session(1);
            assertThrows(IllegalArgumentException.class,
                    () -> rooms.join(client, "pokoj", "Ala", invalid));
            assertEquals(0, rooms.roomCount());
            assertEquals(0, rooms.participantCount());
            assertNull(client.room);
        }
    }

    @Test
    void publishesInEpochOrderWithoutHoldingTheRegistryLock() throws Exception {
        RoomRegistry rooms = new RoomRegistry();
        RoomRegistry.Change first = rooms.join(session(1), "pokoj", "Ala", publicKey());
        RoomRegistry.Change second = rooms.join(session(2), "pokoj", "Ola", publicKey());
        List<Integer> published = new CopyOnWriteArrayList<>();
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstTask = executor.submit(() -> first.publish(() -> {
                firstRunning.countDown();
                await(releaseFirst);
                published.add(first.epoch());
            }));
            try {
                assertTrue(firstRunning.await(2, TimeUnit.SECONDS));
                var secondTask = executor.submit(() -> {
                    secondAttempted.countDown();
                    second.publish(() -> published.add(second.epoch()));
                    secondFinished.countDown();
                });
                assertTrue(secondAttempted.await(2, TimeUnit.SECONDS));
                assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    assertEquals(2, rooms.participantCount());
                    RoomRegistry.Change otherRoom = rooms.join(session(3), "inny", "Iza", publicKey());
                    otherRoom.publish(() -> published.add(100));
                });
                releaseFirst.countDown();
                firstTask.get(2, TimeUnit.SECONDS);
                secondTask.get(2, TimeUnit.SECONDS);
                assertEquals(List.of(100, 1, 2), published);
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    @Test
    void aFailedPublicationDoesNotBlockTheNextEpoch() throws Exception {
        RoomRegistry rooms = new RoomRegistry();
        RoomRegistry.Change first = rooms.join(session(1), "pokoj", "Ala", publicKey());
        RoomRegistry.Change second = rooms.join(session(2), "pokoj", "Ola", publicKey());
        assertThrows(IllegalStateException.class,
                () -> first.publish(() -> { throw new IllegalStateException("writer failed"); }));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> second.publish(() -> { }));
    }

    private static ClientSession session(int tag) {
        return new ClientSession(UUID.randomUUID(), tag, new byte[16], new Socket(),
                new BufferedWriter(new StringWriter()), new ObjectMapper());
    }

    private static String publicKey() throws Exception {
        return Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic().getEncoded());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the test publication");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
