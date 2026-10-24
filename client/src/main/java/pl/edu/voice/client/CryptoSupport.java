package pl.edu.voice.client;

import pl.edu.voice.common.Protocol;
import pl.edu.voice.common.PublicKeys;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

final class CryptoSupport {
    private static final int GROUP_KEY_BYTES = Protocol.GROUP_KEY_BYTES;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoSupport() {
    }

    static KeyPair newIdentityKeyPair() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("X25519").generateKeyPair();
    }

    static SecretKeySpec newGroupKey() {
        byte[] key = new byte[GROUP_KEY_BYTES];
        RANDOM.nextBytes(key);
        return new SecretKeySpec(key, "AES");
    }

    static String encodePublicKey(PublicKey key) {
        return PublicKeys.encode(key);
    }

    static PublicKey decodePublicKey(String encoded) throws GeneralSecurityException {
        return PublicKeys.decode(encoded);
    }

    static WrappedKey wrapGroupKey(SecretKeySpec groupKey, PrivateKey senderPrivate,
                                   PublicKey recipientPublic, String context)
            throws GeneralSecurityException {
        requireGroupKey(groupKey);
        SecretKeySpec wrappingKey = deriveWrappingKey(senderPrivate, recipientPublic, context);
        byte[] nonce = new byte[Protocol.GCM_NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey,
                new GCMParameterSpec(Protocol.GCM_TAG_BITS, nonce));
        cipher.updateAAD(contextBytes(context));
        byte[] ciphertext = cipher.doFinal(groupKey.getEncoded());
        return new WrappedKey(
                Base64.getEncoder().encodeToString(nonce),
                Base64.getEncoder().encodeToString(ciphertext));
    }

    static SecretKeySpec unwrapGroupKey(WrappedKey wrapped, PrivateKey recipientPrivate,
                                        PublicKey senderPublic, String context)
            throws GeneralSecurityException {
        if (wrapped == null || wrapped.nonce() == null || wrapped.ciphertext() == null) {
            throw new GeneralSecurityException("Niepełna koperta klucza");
        }
        SecretKeySpec wrappingKey = deriveWrappingKey(recipientPrivate, senderPublic, context);
        byte[] nonce;
        byte[] ciphertext;
        try {
            nonce = Base64.getDecoder().decode(wrapped.nonce());
            ciphertext = Base64.getDecoder().decode(wrapped.ciphertext());
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("Koperta klucza nie jest poprawnym Base64", exception);
        }
        if (nonce.length != Protocol.GCM_NONCE_BYTES) {
            throw new GeneralSecurityException("Nieprawidłowy nonce koperty klucza");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey,
                new GCMParameterSpec(Protocol.GCM_TAG_BITS, nonce));
        cipher.updateAAD(contextBytes(context));
        byte[] key = cipher.doFinal(ciphertext);
        if (key.length != GROUP_KEY_BYTES) {
            Arrays.fill(key, (byte) 0);
            throw new GeneralSecurityException("Nieprawidłowa długość klucza grupowego");
        }
        return new SecretKeySpec(key, "AES");
    }

    private static SecretKeySpec deriveWrappingKey(PrivateKey privateKey, PublicKey publicKey,
                                                    String context)
            throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        byte[] sharedSecret = agreement.generateSecret();
        try {
            if (isAllZero(sharedSecret)) {
                throw new GeneralSecurityException("Nieprawidłowy klucz publiczny X25519");
            }
            byte[] salt = MessageDigest.getInstance("SHA-256").digest(contextBytes(context));
            byte[] key = hkdfSha256(sharedSecret, salt,
                    ("kek:" + context).getBytes(StandardCharsets.UTF_8), GROUP_KEY_BYTES);
            return new SecretKeySpec(key, "AES");
        } finally {
            Arrays.fill(sharedSecret, (byte) 0);
        }
    }

    /**
     * RFC 5869 HKDF-SHA-256 (extract-then-expand).
     */
    static byte[] hkdfSha256(byte[] inputKeyMaterial, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        if (inputKeyMaterial == null || inputKeyMaterial.length == 0) {
            throw new GeneralSecurityException("Brak materiału wejściowego HKDF");
        }
        if (length < 1 || length > 255 * 32) {
            throw new GeneralSecurityException("Nieprawidłowa długość HKDF");
        }
        byte[] effectiveSalt = salt == null || salt.length == 0 ? new byte[32] : salt;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(effectiveSalt, "HmacSHA256"));
        byte[] pseudoRandomKey = mac.doFinal(inputKeyMaterial);
        byte[] output = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int counter = 1;
        try {
            while (offset < length) {
                mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
                mac.update(previous);
                mac.update(info == null ? new byte[0] : info);
                mac.update((byte) counter++);
                previous = mac.doFinal();
                int copy = Math.min(previous.length, length - offset);
                System.arraycopy(previous, 0, output, offset, copy);
                offset += copy;
            }
        } finally {
            Arrays.fill(pseudoRandomKey, (byte) 0);
            Arrays.fill(previous, (byte) 0);
        }
        return output;
    }

    private static byte[] contextBytes(String context) {
        if (context == null || context.isBlank()) {
            throw new IllegalArgumentException("Kontekst klucza nie może być pusty");
        }
        return context.getBytes(StandardCharsets.UTF_8);
    }

    private static void requireGroupKey(SecretKeySpec groupKey) throws GeneralSecurityException {
        if (groupKey == null || groupKey.getEncoded() == null
                || groupKey.getEncoded().length != GROUP_KEY_BYTES) {
            throw new GeneralSecurityException("Klucz grupowy musi mieć 256 bitów");
        }
    }

    private static boolean isAllZero(byte[] value) {
        int result = 0;
        for (byte element : value) {
            result |= element;
        }
        return result == 0;
    }

    record WrappedKey(String nonce, String ciphertext) {
    }
}
