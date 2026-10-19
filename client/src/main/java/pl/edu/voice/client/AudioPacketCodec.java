package pl.edu.voice.client;

import pl.edu.voice.common.Protocol;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.UUID;

final class AudioPacketCodec {
    private static final int HEADER_BYTES = Integer.BYTES + 1 + 2 * Long.BYTES
            + Integer.BYTES + Integer.BYTES + Long.BYTES;

    private AudioPacketCodec() {
    }

    static byte[] encrypt(UUID senderId, int epoch, int senderTag, long sequence,
                          byte[] opus, SecretKeySpec key) throws GeneralSecurityException {
        byte[] aad = header(senderId, epoch, senderTag, sequence);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key,
                new GCMParameterSpec(Protocol.GCM_TAG_BITS, nonce(senderTag, sequence)));
        cipher.updateAAD(aad);
        byte[] encrypted = cipher.doFinal(opus);
        return ByteBuffer.allocate(aad.length + Short.BYTES + encrypted.length)
                .put(aad)
                .putShort((short) encrypted.length)
                .put(encrypted)
                .array();
    }

    static DecodedPacket decrypt(byte[] datagram, SecretKeySpec key) throws GeneralSecurityException {
        if (datagram.length < HEADER_BYTES + Short.BYTES + 16) {
            throw new IllegalArgumentException("Pakiet audio jest za krótki");
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
        if (length != buffer.remaining()) {
            throw new IllegalArgumentException("Nieprawidłowa długość pakietu audio");
        }
        byte[] ciphertext = new byte[length];
        buffer.get(ciphertext);
        byte[] aad = header(senderId, epoch, senderTag, sequence);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(Protocol.GCM_TAG_BITS, nonce(senderTag, sequence)));
        cipher.updateAAD(aad);
        return new DecodedPacket(senderId, epoch, senderTag, sequence,
                cipher.doFinal(ciphertext));
    }

    static int peekEpoch(byte[] datagram) {
        if (datagram.length < HEADER_BYTES || ByteBuffer.wrap(datagram).getInt() != Protocol.AUDIO_MAGIC) {
            return -1;
        }
        return ByteBuffer.wrap(datagram).getInt(Integer.BYTES + 1 + 2 * Long.BYTES);
    }

    private static byte[] header(UUID senderId, int epoch, int senderTag, long sequence) {
        return ByteBuffer.allocate(HEADER_BYTES)
                .putInt(Protocol.AUDIO_MAGIC)
                .put(Protocol.VERSION)
                .putLong(senderId.getMostSignificantBits())
                .putLong(senderId.getLeastSignificantBits())
                .putInt(epoch)
                .putInt(senderTag)
                .putLong(sequence)
                .array();
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

