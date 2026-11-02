package pl.ciphervoice.client;

import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** A key owns its counters for its entire lifetime, including in-flight packets. */
final class EpochCryptoContext {
    private final int epoch;
    private final SecretKeySpec key;
    private final AtomicLong nextSequence = new AtomicLong();
    private final Map<UUID, Long> receivedSequences = new HashMap<>();

    EpochCryptoContext(int epoch, SecretKeySpec key) {
        this.epoch = epoch;
        this.key = key;
    }

    int epoch() {
        return epoch;
    }

    byte[] encrypt(UUID senderId, int senderTag, byte[] opus)
            throws GeneralSecurityException {
        long sequence = nextSequence.getAndIncrement();
        if (sequence < 0) {
            throw new IllegalStateException("Wyczerpano numery sekwencyjne klucza");
        }
        return AudioPacketCodec.encrypt(senderId, epoch, senderTag, sequence, opus, key);
    }

    AudioPacketCodec.DecodedPacket decrypt(byte[] packet) throws GeneralSecurityException {
        return AudioPacketCodec.decrypt(packet, key);
    }

    synchronized boolean accept(AudioPacketCodec.DecodedPacket packet) {
        if (packet.epoch() != epoch || packet.sequence() < 0) {
            return false;
        }
        Long previous = receivedSequences.get(packet.senderId());
        if (previous != null && packet.sequence() <= previous) {
            return false;
        }
        receivedSequences.put(packet.senderId(), packet.sequence());
        return true;
    }
}
