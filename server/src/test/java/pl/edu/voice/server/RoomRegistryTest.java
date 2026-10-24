package pl.edu.voice.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pl.edu.voice.common.Protocol;
import pl.edu.voice.common.PublicKeys;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomRegistryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger TAGS = new AtomicInteger(1);
    private final RoomRegistry rooms = new RoomRegistry();

    @Test
    void firstParticipantBecomesLeaderAndEpochStartsAtOne() throws Exception {
        ClientSession alice = session();
        RoomRegistry.Change change = rooms.join(alice, "pokoj", "Alice", publicKey());

        assertEquals(1, change.epoch());
        assertEquals(alice.id, change.leaderId());
        assertEquals(1, change.members().size());
        assertEquals(1, rooms.roomCount());
        assertEquals(1, rooms.participantCount());
    }

    @Test
    void duplicateNicknameIsRejectedCaseInsensitively() throws Exception {
        rooms.join(session(), "pokoj", "Alice", publicKey());

        assertThrows(IllegalArgumentException.class,
                () -> rooms.join(session(), "pokoj", "alice", publicKey()));
        assertEquals(1, rooms.participantCount());
    }

    @Test
    void invalidOrMissingPublicKeyIsRejectedAtJoin() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> rooms.join(session(), "pokoj", "Alice", null));
        assertThrows(IllegalArgumentException.class,
                () -> rooms.join(session(), "pokoj", "Alice", "nie-base64!"));
        assertThrows(IllegalArgumentException.class,
                () -> rooms.join(session(), "pokoj", "Alice", "AAAA"));
        assertEquals(0, rooms.participantCount());
    }

    @Test
    void leavingLeaderMovesKeyLeadershipToTheNextMember() throws Exception {
        ClientSession alice = session();
        ClientSession bob = session();
        rooms.join(alice, "pokoj", "Alice", publicKey());
        RoomRegistry.Change afterBob = rooms.join(bob, "pokoj", "Bob", publicKey());
        assertEquals(2, afterBob.epoch());
        assertEquals(alice.id, afterBob.leaderId());

        RoomRegistry.Change afterAlice = rooms.leave(alice);
        assertEquals(3, afterAlice.epoch());
        assertEquals(bob.id, afterAlice.leaderId());
        assertEquals(1, afterAlice.members().size());
        assertFalse(rooms.mayDistribute(alice, bob.id, 3));
        assertTrue(rooms.mayDistribute(bob, bob.id, 3));
    }

    @Test
    void keyDistributionRequiresLeaderCurrentEpochAndTargetInRoom() throws Exception {
        ClientSession alice = session();
        ClientSession bob = session();
        rooms.join(alice, "pokoj", "Alice", publicKey());
        rooms.join(bob, "pokoj", "Bob", publicKey());

        assertTrue(rooms.mayDistribute(alice, bob.id, 2));
        assertFalse(rooms.mayDistribute(bob, alice.id, 2));
        assertFalse(rooms.mayDistribute(alice, bob.id, 1));
        assertFalse(rooms.mayDistribute(alice, UUID.randomUUID(), 2));
        assertTrue(rooms.isCurrentEpoch(alice, 2));
        assertFalse(rooms.isCurrentEpoch(alice, 1));
    }

    @Test
    void lastParticipantLeavingRemovesRoom() throws Exception {
        ClientSession alice = session();
        rooms.join(alice, "pokoj", "Alice", publicKey());
        RoomRegistry.Change change = rooms.leave(alice);

        assertEquals(2, change.epoch());
        assertNull(change.leaderId());
        assertTrue(change.recipients().isEmpty());
        assertEquals(0, rooms.roomCount());
        assertEquals(0, rooms.participantCount());
    }

    private static ClientSession session() {
        return new ClientSession(UUID.randomUUID(), TAGS.getAndIncrement(),
                new byte[Protocol.RELAY_TOKEN_BYTES], new Socket(),
                new BufferedWriter(new StringWriter()), MAPPER);
    }

    private static String publicKey() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        return PublicKeys.encode(pair.getPublic());
    }
}
