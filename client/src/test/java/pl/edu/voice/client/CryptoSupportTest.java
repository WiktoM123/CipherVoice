package pl.edu.voice.client;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CryptoSupportTest {
    @Test
    void leaderCanWrapGroupKeyForAnotherMember() throws Exception {
        KeyPair leader = CryptoSupport.newIdentityKeyPair();
        KeyPair member = CryptoSupport.newIdentityKeyPair();
        SecretKeySpec groupKey = CryptoSupport.newGroupKey();
        String context = "voice-key/v1/test/1/leader/member";

        CryptoSupport.WrappedKey wrapped = CryptoSupport.wrapGroupKey(
                groupKey, leader.getPrivate(), member.getPublic(), context);
        SecretKeySpec unwrapped = CryptoSupport.unwrapGroupKey(
                wrapped, member.getPrivate(), leader.getPublic(), context);

        assertArrayEquals(groupKey.getEncoded(), unwrapped.getEncoded());
    }

    @Test
    void changedAudioHeaderFailsAuthentication() throws Exception {
        SecretKeySpec key = CryptoSupport.newGroupKey();
        UUID sender = UUID.randomUUID();
        byte[] packet = AudioPacketCodec.encrypt(sender, 7, 1234, 10,
                new byte[]{1, 2, 3, 4}, key);
        packet[packet.length - 1] ^= 1;

        assertThrows(AEADBadTagException.class, () -> AudioPacketCodec.decrypt(packet, key));
    }
}
