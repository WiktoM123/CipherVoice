package pl.edu.voice.common;

public final class Protocol {
    public static final int AUDIO_MAGIC = 0x564F4950; // VOIP
    public static final int REGISTER_MAGIC = 0x56435247; // VCRG
    public static final byte VERSION = 1;
    public static final int RELAY_TOKEN_BYTES = 16;
    public static final int GCM_TAG_BITS = 128;
    public static final int GCM_NONCE_BYTES = 12;
    public static final int MAX_DATAGRAM_BYTES = 4096;

    private Protocol() {
    }
}
