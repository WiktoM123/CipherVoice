package pl.edu.voice.common;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicKeysTest {
    @Test
    void encodesAndDecodesX25519() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        String encoded = PublicKeys.encode(pair.getPublic());

        assertEquals(pair.getPublic(), PublicKeys.decode(encoded));
    }

    @Test
    void rejectsMalformedKeys() {
        assertThrows(GeneralSecurityException.class, () -> PublicKeys.decode(null));
        assertThrows(GeneralSecurityException.class, () -> PublicKeys.decode("!!!"));
        assertThrows(GeneralSecurityException.class, () -> PublicKeys.decode(
                "A".repeat(Protocol.MAX_PUBLIC_KEY_CHARS + 1)));
        assertThrows(GeneralSecurityException.class, () -> PublicKeys.decode("AAAA"));
    }
}
