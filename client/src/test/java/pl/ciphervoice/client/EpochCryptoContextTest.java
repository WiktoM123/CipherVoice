package pl.ciphervoice.client;

import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpochCryptoContextTest {
    @Test
    void inFlightSenderKeepsTheOldKeyEpochAndCounterAfterRekey() throws Exception {
        UUID sender = UUID.randomUUID();
        SecretKeySpec oldKey = CryptoSupport.newGroupKey();
        EpochCryptoContext oldContext = new EpochCryptoContext(1, oldKey);
        assertEquals(0, AudioPacketCodec.decrypt(oldContext.encrypt(sender, 42,
                new byte[]{1}), oldKey).sequence());

        // The capture thread retains this snapshot while control installs a new context.
        EpochCryptoContext inFlight = oldContext;
        SecretKeySpec newKey = CryptoSupport.newGroupKey();
        EpochCryptoContext current = new EpochCryptoContext(2, newKey);
        AudioPacketCodec.DecodedPacket late = AudioPacketCodec.decrypt(
                inFlight.encrypt(sender, 42, new byte[]{2}), oldKey);
        AudioPacketCodec.DecodedPacket fresh = AudioPacketCodec.decrypt(
                current.encrypt(sender, 42, new byte[]{3}), newKey);

        assertEquals(1, late.epoch());
        assertEquals(1, late.sequence());
        assertEquals(2, fresh.epoch());
        assertEquals(0, fresh.sequence());
    }

    @Test
    void lateOldPacketCannotPoisonReplayStateOfTheNewEpoch() {
        UUID sender = UUID.randomUUID();
        EpochCryptoContext oldContext = new EpochCryptoContext(1, CryptoSupport.newGroupKey());
        EpochCryptoContext current = new EpochCryptoContext(2, CryptoSupport.newGroupKey());
        AudioPacketCodec.DecodedPacket oldPacket = packet(sender, 1, 10_000);
        assertTrue(oldContext.accept(oldPacket));
        assertFalse(current.accept(oldPacket));
        assertTrue(current.accept(packet(sender, 2, 0)));
        assertFalse(current.accept(packet(sender, 2, 0)));
        assertFalse(current.accept(packet(sender, 2, -1)));
        assertTrue(current.accept(packet(sender, 2, 1)));
    }

    @Test
    void concurrentSendersNeverReuseASequenceWithinTheSameKey() {
        SecretKeySpec key = CryptoSupport.newGroupKey();
        EpochCryptoContext context = new EpochCryptoContext(9, key);
        UUID sender = UUID.randomUUID();
        Set<Long> sequences = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 128).parallel().forEach(ignored -> {
            try {
                byte[] encrypted = context.encrypt(sender, 7, new byte[]{1});
                assertTrue(sequences.add(AudioPacketCodec.decrypt(encrypted, key).sequence()));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });

        assertEquals(128, sequences.size());
        assertTrue(sequences.contains(0L));
        assertTrue(sequences.contains(127L));
    }

    private static AudioPacketCodec.DecodedPacket packet(UUID sender, int epoch, long sequence) {
        return new AudioPacketCodec.DecodedPacket(sender, epoch, 42, sequence, new byte[]{1});
    }
}
