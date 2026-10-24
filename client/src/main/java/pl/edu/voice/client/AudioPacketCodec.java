package pl.edu.voice.client;

import pl.edu.voice.common.Protocol;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * Binary UDP packet format:
 *
 * <pre>
 * magic(4) | version(1) | senderId(16) | epoch(4) | senderTag(4) |
 * sequence(8) | ciphertextLength(2) | ciphertext + GCM tag
 * </pre>
 *
 * The whole header including the length field is authenticated as AAD, so no
 * part of the packet can be altered without the GCM check failing.
 */
final class AudioPacketCodec {
    private static final int MAGIC_OFFSET = 0;
    private static final int VERSION_OFFSET = Integer.BYTES;
    private static final int SENDER_ID_OFFSET = VERSION_OFFSET + Byte.BYTES;
    private static final int EPOCH_OFFSET = SENDER_ID_OFFSET + 2 * Long.BYTES;
    private static final int SENDER_TAG_OFFSET = EPOCH_OFFSET + Integer.BYTES;
    private static final int SEQUENCE_OFFSET = SENDER_TAG_OFFSET + Integer.BYTES;
    private static final int LENGTH_OFFSET = SEQUENCE_OFFSET + Long.BYTES;
    private static final int HEADER_BYTES = LENGTH_OFFSET + Short.BYTES;

    private AudioPacketCodec() {
    }

    static byte[] encrypt(UUID senderId, int epoch, int senderTag, long sequence,
                          byte[] opus, SecretKeySpec key) throws GeneralSecurityException {
        if (senderId == null || opus == null || opus.length == 0 || key == null) {
            throw new IllegalArgumentException("Niepełne dane pakietu audio");
        }
        int ciphertextLength = opus.length + Protocol.GCM_TAG_BYTES;
        int packetLength = HEADER_BYTES + ciphertextLength;
        if (packetLength > Protocol.MAX_DATAGRAM_BYTES) {
            throw new IllegalArgumentException("Pakiet audio jest zbyt duży");
        }

        ByteBuffer packet = ByteBuffer.allocate(packetLength);
        putHeader(packet, senderId, epoch, senderTag, sequence, ciphertextLength);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key,
                new GCMParameterSpec(Protocol.GCM_TAG_BITS, nonce(senderTag, sequence)));
        cipher.updateAAD(packet.array(), 0, HEADER_BYTES);
        byte[] encrypted = cipher.doFinal(opus);
        packet.put(encrypted);
        return packet.array();
    }

    static DecodedPacket decrypt(byte[] datagram, SecretKeySpec key)
            throws GeneralSecurityException {
        if (datagram == null || key == null
                || datagram.length < HEADER_BYTES + Protocol.GCM_TAG_BYTES
                || datagram.length > Protocol.MAX_DATAGRAM_BYTES) {
            throw new IllegalArgumentException("Pakiet audio ma nieprawidłowy rozmiar");
        }
        ByteBuffer buffer = ByteBuffer.wrap(datagram);
        int magic = buffer.getInt();
        byte version = buffer.get();
        if (magic != Protocol.AUDIO_MAGIC || version != Protocol.VERSION) {
            throw new IllegalArgumentException("Nieprawidłowy pakiet audio");
        }
        UUID senderId = new UUID(buffer.getLong(), buffer.getLong());
        int epoch = buffer.getInt();
        int senderTag = buffer.getInt();
        long sequence = buffer.getLong();
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length < Protocol.GCM_TAG_BYTES || length != buffer.remaining()) {
            throw new IllegalArgumentException("Nieprawidłowa długość pakietu audio");
        }
        byte[] ciphertext = new byte[length];
        buffer.get(ciphertext);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(Protocol.GCM_TAG_BITS, nonce(senderTag, sequence)));
        cipher.updateAAD(datagram, 0, HEADER_BYTES);
        byte[] opus = cipher.doFinal(ciphertext);
        return new DecodedPacket(senderId, epoch, senderTag, sequence, opus);
    }

    /**
     * Reads the epoch of a packet that at least has a complete header.
     *
     * @return the epoch, or {@code -1} for a packet with an unknown magic/version
     */
    static int peekEpoch(byte[] datagram) {
        if (datagram == null || datagram.length < HEADER_BYTES) {
            return -1;
        }
        ByteBuffer buffer = ByteBuffer.wrap(datagram);
        if (buffer.getInt(MAGIC_OFFSET) != Protocol.AUDIO_MAGIC
                || datagram[VERSION_OFFSET] != Protocol.VERSION) {
            return -1;
        }
        return buffer.getInt(EPOCH_OFFSET);
    }

    private static void putHeader(ByteBuffer packet, UUID senderId, int epoch, int senderTag,
                                  long sequence, int ciphertextLength) {
        packet.putInt(Protocol.AUDIO_MAGIC)
                .put(Protocol.VERSION)
                .putLong(senderId.getMostSignificantBits())
                .putLong(senderId.getLeastSignificantBits())
                .putInt(epoch)
                .putInt(senderTag)
                .putLong(sequence)
                .putShort((short) ciphertextLength);
    }

    private static byte[] nonce(int senderTag, long sequence) {
        return ByteBuffer.allocate(Protocol.GCM_NONCE_BYTES)
                .putInt(senderTag)
                .putLong(sequence)
                .array();
    }

    record DecodedPacket(UUID senderId, int epoch, int senderTag, long sequence, byte[] opus) {
    }
}
