package pl.edu.voice.common;

public final class Protocol {
    /** Bumped when the authenticated packet layout changes. */
    public static final byte VERSION = 3;
    public static final int AUDIO_MAGIC = 0x564F4950; // VOIP
    public static final int REGISTER_MAGIC = 0x56435247; // VCRG
    public static final int RELAY_TOKEN_BYTES = 16;
    public static final int GROUP_KEY_BYTES = 32;
    public static final int GCM_TAG_BITS = 128;
    public static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;
    public static final int WRAPPED_KEY_BYTES = GROUP_KEY_BYTES + GCM_TAG_BYTES;
    public static final int GCM_NONCE_BYTES = 12;
    /** Global per-key invocation bound for random GCM nonces (NIST SP 800-38D, 8.3). */
    public static final long MAX_RANDOM_NONCE_ENCRYPTIONS_PER_KEY = 1L << 32;
    public static final int MAX_DATAGRAM_BYTES = 4096;
    public static final int AUDIO_HEADER_BYTES =
            4 + 1 + 16 + 4 + 4 + 8 + GCM_NONCE_BYTES + 2;
    public static final int MIN_AUDIO_DATAGRAM_BYTES =
            AUDIO_HEADER_BYTES + GCM_TAG_BYTES;
    public static final int REGISTER_DATAGRAM_BYTES =
            4 + 1 + 16 + RELAY_TOKEN_BYTES;
    /** Maximum length of one newline-delimited JSON control message. */
    public static final int MAX_CONTROL_LINE_CHARS = 65_536;
    /** X25519 public keys encoded as Base64 X.509 are about 60 characters. */
    public static final int MAX_PUBLIC_KEY_CHARS = 256;

    private Protocol() {
    }
}
