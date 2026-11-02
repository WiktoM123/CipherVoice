package pl.ciphervoice.client;

import javax.crypto.spec.SecretKeySpec;
import pl.ciphervoice.common.Protocol;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** A key owns its counters for its entire lifetime, including in-flight packets. */
final class EpochCryptoContext {
    private final int epoch;
    private final SecretKeySpec key;
    private final long packetLimit;
    private final AtomicLong encryptedPackets = new AtomicLong();
    private final AtomicLong nextSequence = new AtomicLong();
    private final Map<UUID, Long> receivedSequences = new HashMap<>();

    EpochCryptoContext(int epoch, SecretKeySpec key) {
        this(epoch, key, 1);
    }

    EpochCryptoContext(int epoch, SecretKeySpec key, int participants) {
        this.epoch = epoch;
        this.key = key;
        this.packetLimit = Protocol.MAX_RANDOM_NONCE_ENCRYPTIONS_PER_KEY
                / Math.max(1, participants);
    }

    int epoch() {
        return epoch;
    }

    SecretKeySpec key() {
        return key;
    }

    long packetLimit() {
        return packetLimit;
    }

    long claimEncryption() {
        return encryptedPackets.getAndUpdate(count -> Math.min(count + 1, packetLimit + 1));
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
