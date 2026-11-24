package pl.ciphervoice.client;

import org.junit.jupiter.api.Test;
import pl.ciphervoice.common.MemberInfo;

import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SasVerificationTest {
    private static final UUID LEADER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final MemberInfo LEADER_MEMBER =
            new MemberInfo(LEADER.toString(), "Leader", "leader-public-key", 7);
    private static final MemberInfo ALICE_MEMBER =
            new MemberInfo(ALICE.toString(), "Alice", "test-public-key", 42);
    private static final SecretKeySpec KEY = key(0);
    private static final SecretKeySpec OTHER_KEY = key(1);

    @Test
    void derivesPinnedSixDigitCode() throws Exception {
        String code = compute(KEY, "pokoj", 2, LEADER, LEADER_MEMBER, ALICE_MEMBER);

        assertEquals("035 278", code);
        assertTrue(code.matches("\\d{3} \\d{3}"));
        assertEquals("000 000", SasVerification.format(0));
        assertEquals("482 913", SasVerification.format(482_913));
        assertThrows(IllegalArgumentException.class,
                () -> SasVerification.format(1_000_000));
    }

    @Test
    void rosterOrderDoesNotChangeTheCode() throws Exception {
        String first = compute(KEY, "pokoj", 2, LEADER, LEADER_MEMBER, ALICE_MEMBER);
        String reversed = compute(KEY, "pokoj", 2, LEADER, ALICE_MEMBER, LEADER_MEMBER);

        assertEquals(first, reversed);
    }

    @Test
    void keyEpochRoomAndLeaderAreBoundIntoTheCode() throws Exception {
        String baseline = compute(KEY, "pokoj", 2, LEADER, LEADER_MEMBER, ALICE_MEMBER);

        assertNotEquals(baseline,
                compute(OTHER_KEY, "pokoj", 2, LEADER, LEADER_MEMBER, ALICE_MEMBER));
        assertNotEquals(baseline,
                compute(KEY, "pokoj", 3, LEADER, LEADER_MEMBER, ALICE_MEMBER));
        assertNotEquals(baseline,
                compute(KEY, "inny-pokoj", 2, LEADER, LEADER_MEMBER, ALICE_MEMBER));
        assertNotEquals(baseline,
                compute(KEY, "pokoj", 2, ALICE, LEADER_MEMBER, ALICE_MEMBER));
    }

    @Test
    void everyMemberFieldIsBoundIntoTheCode() throws Exception {
        String baseline = compute(KEY, "pokoj", 2, LEADER, LEADER_MEMBER, ALICE_MEMBER);

        assertNotEquals(baseline, compute(KEY, "pokoj", 2, LEADER, LEADER_MEMBER,
                new MemberInfo(ALICE.toString(), "Mallory", "test-public-key", 42)));
        assertNotEquals(baseline, compute(KEY, "pokoj", 2, LEADER, LEADER_MEMBER,
                new MemberInfo(ALICE.toString(), "Alice", "other-public-key", 42)));
        assertNotEquals(baseline, compute(KEY, "pokoj", 2, LEADER, LEADER_MEMBER,
                new MemberInfo(ALICE.toString(), "Alice", "test-public-key", 43)));
    }

    @Test
    void membershipChangeChangesTheCode() throws Exception {
        String baseline = compute(KEY, "pokoj", 2, LEADER, LEADER_MEMBER, ALICE_MEMBER);

        assertNotEquals(baseline, compute(KEY, "pokoj", 2, LEADER, LEADER_MEMBER));

        UUID third = UUID.fromString("00000000-0000-4000-8000-000000000003");
        assertNotEquals(baseline, compute(KEY, "pokoj", 2, LEADER, LEADER_MEMBER,
                ALICE_MEMBER, new MemberInfo(third.toString(), "Bob", "bob-key", 9)));
    }

    @Test
    void rejectsMissingOrAmbiguousInput() {
        assertThrows(Exception.class,
                () -> SasVerification.compute(null, "pokoj", 2, LEADER,
                        List.of(LEADER_MEMBER, ALICE_MEMBER)));
        assertThrows(Exception.class,
                () -> SasVerification.compute(new SecretKeySpec(new byte[16], "AES"),
                        "pokoj", 2, LEADER, List.of(LEADER_MEMBER, ALICE_MEMBER)));
        assertThrows(IllegalArgumentException.class,
                () -> compute(KEY, "  ", 2, LEADER, LEADER_MEMBER, ALICE_MEMBER));
        assertThrows(IllegalArgumentException.class,
                () -> SasVerification.compute(KEY, "pokoj", -1, LEADER,
                        List.of(LEADER_MEMBER, ALICE_MEMBER)));
        assertThrows(IllegalArgumentException.class,
                () -> SasVerification.compute(KEY, "pokoj", 2, null,
                        List.of(LEADER_MEMBER, ALICE_MEMBER)));
        assertThrows(IllegalArgumentException.class,
                () -> SasVerification.compute(KEY, "pokoj", 2, LEADER, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> compute(KEY, "pokoj", 2, LEADER, ALICE_MEMBER));
        assertThrows(IllegalArgumentException.class,
                () -> compute(KEY, "pokoj", 2, LEADER, LEADER_MEMBER, LEADER_MEMBER));
        assertThrows(IllegalArgumentException.class,
                () -> SasVerification.compute(KEY, "pokoj", 2, LEADER,
                        Arrays.asList(LEADER_MEMBER, null)));
        assertThrows(IllegalArgumentException.class,
                () -> compute(KEY, "pokoj", 2, LEADER,
                        new MemberInfo("not-a-uuid", "Alice", "test-public-key", 42)));
        assertThrows(IllegalArgumentException.class,
                () -> compute(KEY, "pokoj", 2, LEADER,
                        new MemberInfo(ALICE.toString(), " ", "test-public-key", 42)));
        assertThrows(IllegalArgumentException.class,
                () -> compute(KEY, "pokoj", 2, LEADER,
                        new MemberInfo(ALICE.toString(), "Alice", " ", 42)));
    }

    private static String compute(SecretKeySpec key, String room, int epoch, UUID leader,
                                  MemberInfo... members) throws Exception {
        return SasVerification.compute(key, room, epoch, leader, new ArrayList<>(List.of(members)));
    }

    private static SecretKeySpec key(int start) {
        byte[] value = new byte[32];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (start + i);
        }
        return new SecretKeySpec(value, "AES");
    }
}
