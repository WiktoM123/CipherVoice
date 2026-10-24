package pl.edu.voice.common;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Shared X25519 public-key encoding used by the client and validated by the server. */
public final class PublicKeys {
    private PublicKeys() {
    }

    public static String encode(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decode(String encoded) throws GeneralSecurityException {
        if (encoded == null || encoded.isBlank()
                || encoded.length() > Protocol.MAX_PUBLIC_KEY_CHARS) {
            throw new GeneralSecurityException("Nieprawidłowy klucz publiczny");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException(
                    "Klucz publiczny nie jest poprawnym Base64", exception);
        }
        PublicKey key = KeyFactory.getInstance("X25519")
                .generatePublic(new X509EncodedKeySpec(bytes));
        String algorithm = key.getAlgorithm();
        if (!"X25519".equalsIgnoreCase(algorithm) && !"XDH".equalsIgnoreCase(algorithm)) {
            throw new GeneralSecurityException("Nieobsługiwany algorytm klucza publicznego");
        }
        return key;
    }
}
