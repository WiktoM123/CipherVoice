package pl.edu.voice.client;

import pl.edu.voice.common.Protocol;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

final class CryptoSupport {
    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoSupport() {
    }

    static KeyPair newIdentityKeyPair() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("X25519").generateKeyPair();
    }

    static SecretKeySpec newGroupKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return new SecretKeySpec(key, "AES");
    }

    static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    static PublicKey decodePublicKey(String encoded) throws GeneralSecurityException {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(bytes));
    }

    static WrappedKey wrapGroupKey(SecretKeySpec groupKey, PrivateKey senderPrivate,
                                   PublicKey recipientPublic, String context)
            throws GeneralSecurityException {
        SecretKeySpec wrappingKey = deriveWrappingKey(senderPrivate, recipientPublic, context);
        byte[] nonce = new byte[Protocol.GCM_NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey,
                new GCMParameterSpec(Protocol.GCM_TAG_BITS, nonce));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(groupKey.getEncoded());
        return new WrappedKey(
                Base64.getEncoder().encodeToString(nonce),
                Base64.getEncoder().encodeToString(ciphertext));
    }

    static SecretKeySpec unwrapGroupKey(WrappedKey wrapped, PrivateKey recipientPrivate,
                                        PublicKey senderPublic, String context)
            throws GeneralSecurityException {
        SecretKeySpec wrappingKey = deriveWrappingKey(recipientPrivate, senderPublic, context);
        byte[] nonce = Base64.getDecoder().decode(wrapped.nonce());
        byte[] ciphertext = Base64.getDecoder().decode(wrapped.ciphertext());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey,
                new GCMParameterSpec(Protocol.GCM_TAG_BITS, nonce));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(cipher.doFinal(ciphertext), "AES");
    }

    private static SecretKeySpec deriveWrappingKey(PrivateKey privateKey, PublicKey publicKey,
                                                    String context)
            throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        byte[] sharedSecret = agreement.generateSecret();
        byte[] salt = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("salt:" + context).getBytes(StandardCharsets.UTF_8));
        byte[] key = hkdfSha256(sharedSecret, salt,
                ("kek:" + context).getBytes(StandardCharsets.UTF_8), 32);
        Arrays.fill(sharedSecret, (byte) 0);
        return new SecretKeySpec(key, "AES");
    }

    private static byte[] hkdfSha256(byte[] inputKeyMaterial, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] pseudoRandomKey = mac.doFinal(inputKeyMaterial);
        byte[] output = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int counter = 1;
        while (offset < length) {
            mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
            mac.update(previous);
            mac.update(info);
            mac.update((byte) counter++);
            previous = mac.doFinal();
            int copy = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, output, offset, copy);
            offset += copy;
        }
        Arrays.fill(pseudoRandomKey, (byte) 0);
        return output;
    }

    record WrappedKey(String nonce, String ciphertext) {
    }
}

