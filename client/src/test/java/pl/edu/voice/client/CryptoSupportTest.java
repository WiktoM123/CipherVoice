package pl.edu.voice.client;

import org.junit.jupiter.api.Test;
import pl.edu.voice.common.Protocol;

import javax.crypto.AEADBadTagException;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoSupportTest {
    private static final String CONTEXT = "voice-key/v2/test/1/leader/member";

    @Test
    void leaderCanWrapGroupKeyForAnotherMember() throws Exception {
        KeyPair leader = CryptoSupport.newIdentityKeyPair();
        KeyPair member = CryptoSupport.newIdentityKeyPair();
        SecretKeySpec groupKey = CryptoSupport.newGroupKey();

        CryptoSupport.WrappedKey wrapped = CryptoSupport.wrapGroupKey(
                groupKey, leader.getPrivate(), member.getPublic(), CONTEXT);
        SecretKeySpec unwrapped = CryptoSupport.unwrapGroupKey(
                wrapped, member.getPrivate(), leader.getPublic(), CONTEXT);

        assertArrayEquals(groupKey.getEncoded(), unwrapped.getEncoded());
    }

    @Test
    void envelopeForAnotherContextCannotBeUnwrapped() throws Exception {
        KeyPair leader = CryptoSupport.newIdentityKeyPair();
        KeyPair member = CryptoSupport.newIdentityKeyPair();
        CryptoSupport.WrappedKey wrapped = CryptoSupport.wrapGroupKey(
                CryptoSupport.newGroupKey(), leader.getPrivate(), member.getPublic(), CONTEXT);

        assertThrows(AEADBadTagException.class, () -> CryptoSupport.unwrapGroupKey(
                wrapped, member.getPrivate(), leader.getPublic(),
                "voice-key/v2/test/2/leader/member"));
    }

    @Test
    void tamperedEnvelopeIsRejected() throws Exception {
        KeyPair leader = CryptoSupport.newIdentityKeyPair();
        KeyPair member = CryptoSupport.newIdentityKeyPair();
        CryptoSupport.WrappedKey wrapped = CryptoSupport.wrapGroupKey(
                CryptoSupport.newGroupKey(), leader.getPrivate(), member.getPublic(), CONTEXT);
        byte[] ciphertext = java.util.Base64.getDecoder().decode(wrapped.ciphertext());
        ciphertext[0] ^= 1;
        CryptoSupport.WrappedKey tampered = new CryptoSupport.WrappedKey(
                wrapped.nonce(), java.util.Base64.getEncoder().encodeToString(ciphertext));

        assertThrows(AEADBadTagException.class, () -> CryptoSupport.unwrapGroupKey(
                tampered, member.getPrivate(), leader.getPublic(), CONTEXT));
    }

    @Test
    void envelopeCannotBeOpenedWithAnotherPrivateKey() throws Exception {
        KeyPair leader = CryptoSupport.newIdentityKeyPair();
        KeyPair member = CryptoSupport.newIdentityKeyPair();
        KeyPair stranger = CryptoSupport.newIdentityKeyPair();
        CryptoSupport.WrappedKey wrapped = CryptoSupport.wrapGroupKey(
                CryptoSupport.newGroupKey(), leader.getPrivate(), member.getPublic(), CONTEXT);

        assertThrows(AEADBadTagException.class, () -> CryptoSupport.unwrapGroupKey(
                wrapped, stranger.getPrivate(), leader.getPublic(), CONTEXT));
    }

    @Test
    void hkdfMatchesRfc5869TestCaseOne() throws Exception {
        byte[] ikm = fill(22, (byte) 0x0b);
        byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c");
        byte[] info = HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");

        byte[] okm = CryptoSupport.hkdfSha256(ikm, salt, info, 42);

        assertArrayEquals(HexFormat.of().parseHex(
                "3cb25f25faacd57a90434f64d0362f2a"
                        + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                        + "34007208d5b887185865"), okm);
    }

    @Test
    void newGroupKeyIsAes256() {
        byte[] key = CryptoSupport.newGroupKey().getEncoded();
        assertEquals(32, key.length);
    }

    @Test
    void invalidPublicKeysAreRejected() {
        assertThrows(GeneralSecurityException.class,
                () -> CryptoSupport.decodePublicKey("to nie jest Base64!"));
        assertThrows(GeneralSecurityException.class,
                () -> CryptoSupport.decodePublicKey("A".repeat(Protocol.MAX_PUBLIC_KEY_CHARS + 1)));
        assertThrows(GeneralSecurityException.class,
                () -> CryptoSupport.decodePublicKey(null));
    }

    @Test
    void audioPacketRoundTripPreservesAllFields() throws Exception {
        SecretKeySpec key = CryptoSupport.newGroupKey();
        UUID sender = UUID.randomUUID();
        byte[] opus = {1, 2, 3, 4, 5};

        byte[] packet = AudioPacketCodec.encrypt(sender, 7, 1234, 10, opus, key);
        AudioPacketCodec.DecodedPacket decoded = AudioPacketCodec.decrypt(packet, key);

        assertEquals(sender, decoded.senderId());
        assertEquals(7, decoded.epoch());
        assertEquals(1234, decoded.senderTag());
        assertEquals(10, decoded.sequence());
        assertArrayEquals(opus, decoded.opus());
        assertEquals(7, AudioPacketCodec.peekEpoch(packet));
    }

    @Test
    void changedAudioHeaderFailsAuthentication() throws Exception {
        SecretKeySpec key = CryptoSupport.newGroupKey();
        byte[] packet = AudioPacketCodec.encrypt(UUID.randomUUID(), 7, 1234, 10,
                new byte[]{1, 2, 3, 4}, key);
        packet[21] ^= 1; // epoch is part of the authenticated header

        assertThrows(AEADBadTagException.class, () -> AudioPacketCodec.decrypt(packet, key));
    }

    @Test
    void changedLengthFieldIsRejected() throws Exception {
        SecretKeySpec key = CryptoSupport.newGroupKey();
        byte[] packet = AudioPacketCodec.encrypt(UUID.randomUUID(), 7, 1234, 10,
                new byte[]{1, 2, 3, 4}, key);
        packet[38] ^= 1; // low byte of ciphertextLength

        assertThrows(IllegalArgumentException.class,
                () -> AudioPacketCodec.decrypt(packet, key));
    }

    @Test
    void wrongKeyAndTruncatedPacketsAreRejected() throws Exception {
        SecretKeySpec key = CryptoSupport.newGroupKey();
        SecretKeySpec otherKey = CryptoSupport.newGroupKey();
        byte[] packet = AudioPacketCodec.encrypt(UUID.randomUUID(), 1, 42, 0,
                new byte[]{9, 8, 7}, key);

        assertThrows(AEADBadTagException.class,
                () -> AudioPacketCodec.decrypt(packet, otherKey));
        assertThrows(IllegalArgumentException.class,
                () -> AudioPacketCodec.decrypt(java.util.Arrays.copyOf(packet, 20), key));
        assertEquals(-1, AudioPacketCodec.peekEpoch(new byte[0]));
        assertEquals(-1, AudioPacketCodec.peekEpoch(new byte[Protocol.MAX_DATAGRAM_BYTES]));
    }

    @Test
    void oversizedOpusFrameIsRejected() {
        byte[] tooLarge = new byte[Protocol.MAX_DATAGRAM_BYTES];
        assertThrows(IllegalArgumentException.class, () -> AudioPacketCodec.encrypt(
                UUID.randomUUID(), 1, 1, 1, tooLarge, CryptoSupport.newGroupKey()));
    }

    @Test
    void sequenceAndTagProduceDistinctPackets() throws Exception {
        SecretKeySpec key = CryptoSupport.newGroupKey();
        UUID sender = UUID.randomUUID();
        byte[] first = AudioPacketCodec.encrypt(sender, 1, 100, 0, new byte[]{1}, key);
        byte[] second = AudioPacketCodec.encrypt(sender, 1, 100, 1, new byte[]{1}, key);
        byte[] otherSender = AudioPacketCodec.encrypt(sender, 1, 101, 0, new byte[]{1}, key);

        assertTrue(!java.util.Arrays.equals(first, second));
        assertTrue(!java.util.Arrays.equals(first, otherSender));
    }

    private static byte[] fill(int length, byte value) {
        byte[] result = new byte[length];
        java.util.Arrays.fill(result, value);
        return result;
    }
}
