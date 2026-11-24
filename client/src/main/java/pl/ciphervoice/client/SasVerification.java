package pl.ciphervoice.client;

import pl.ciphervoice.common.MemberInfo;
import pl.ciphervoice.common.Protocol;

import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Local short authentication string (SAS) for the whole room.
 *
 * <p>The code is derived from the group key and a canonical transcript of the
 * room, epoch, leader and every member identity. It is never sent through the
 * server: each client computes it independently and users compare the codes
 * outside the encrypted voice channel.</p>
 */
final class SasVerification {
    static final String DOMAIN = "ciphervoice-sas/v1";
    static final int CODE_MODULUS = 1_000_000;
    private static final int DERIVED_BYTES = 8;

    private SasVerification() {
    }

    /**
     * Derives a six-digit code formatted as {@code 123 456}.
     *
     * <p>The member list is sorted by UUID and serialized with 4-byte length
     * prefixes. This makes the transcript independent of message order and
     * unambiguous even when nicknames or public keys contain separators.</p>
     */
    static String compute(SecretKeySpec groupKey, String room, int epoch, UUID leaderId,
                          Collection<MemberInfo> members) throws GeneralSecurityException {
        if (groupKey == null || groupKey.getEncoded() == null
                || groupKey.getEncoded().length != Protocol.GROUP_KEY_BYTES) {
            throw new GeneralSecurityException("Klucz grupowy SAS musi mieć 256 bitów");
        }
        if (room == null || room.isBlank()) {
            throw new IllegalArgumentException("Nazwa pokoju SAS nie może być pusta");
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("Epoka SAS nie może być ujemna");
        }
        if (leaderId == null) {
            throw new IllegalArgumentException("Brak lidera w transkrypcie SAS");
        }
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("Lista uczestników SAS nie może być pusta");
        }

        List<CanonicalMember> roster = canonicalRoster(members);
        if (roster.stream().noneMatch(member -> member.id().equals(leaderId))) {
            throw new IllegalArgumentException("Lider nie występuje na liście uczestników SAS");
        }

        byte[] transcript = transcript(room, epoch, leaderId, roster);
        byte[] derived = CryptoSupport.hkdfSha256(
                groupKey.getEncoded(),
                DOMAIN.getBytes(StandardCharsets.UTF_8),
                transcript,
                DERIVED_BYTES);
        int value = new BigInteger(1, derived).mod(BigInteger.valueOf(CODE_MODULUS)).intValue();
        return format(value);
    }

    static String format(int value) {
        if (value < 0 || value >= CODE_MODULUS) {
            throw new IllegalArgumentException("Wartość SAS poza zakresem");
        }
        String digits = String.format(Locale.ROOT, "%06d", value);
        return digits.substring(0, 3) + " " + digits.substring(3);
    }

    private static List<CanonicalMember> canonicalRoster(Collection<MemberInfo> members) {
        List<CanonicalMember> roster = new ArrayList<>(members.size());
        Set<UUID> seen = new HashSet<>();
        for (MemberInfo member : members) {
            if (member == null) {
                throw new IllegalArgumentException("Niepełne dane uczestnika SAS");
            }
            UUID id;
            try {
                id = UUID.fromString(member.clientId());
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Nieprawidłowy identyfikator uczestnika SAS",
                        exception);
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Powtórzony uczestnik w danych SAS");
            }
            if (member.nickname() == null || member.nickname().isBlank()) {
                throw new IllegalArgumentException("Brak nickname uczestnika SAS");
            }
            if (member.publicKey() == null || member.publicKey().isBlank()) {
                throw new IllegalArgumentException("Brak klucza publicznego uczestnika SAS");
            }
            roster.add(new CanonicalMember(id, member));
        }
        roster.sort(Comparator.comparing(CanonicalMember::id));
        return roster;
    }

    private static byte[] transcript(String room, int epoch, UUID leaderId,
                                      List<CanonicalMember> roster) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        writeField(out, DOMAIN);
        writeField(out, room);
        writeInt(out, epoch);
        writeField(out, leaderId.toString());
        writeInt(out, roster.size());
        for (CanonicalMember entry : roster) {
            writeField(out, entry.id().toString());
            writeField(out, entry.info().nickname());
            writeField(out, entry.info().publicKey());
            writeInt(out, entry.info().senderTag());
        }
        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private record CanonicalMember(UUID id, MemberInfo info) {
    }
}
