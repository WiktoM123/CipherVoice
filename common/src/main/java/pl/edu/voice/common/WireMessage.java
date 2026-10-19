package pl.edu.voice.common;

import java.util.ArrayList;
import java.util.List;

/** Newline-delimited JSON message used by the TCP control channel. */
public class WireMessage {
    public MessageType type;
    public String room;
    public String nickname;
    public String clientId;
    public String publicKey;
    public Integer senderTag;
    public String leaderId;
    public Integer epoch;
    public String targetId;
    public String wrapNonce;
    public String wrappedKey;
    public String relayToken;
    public String error;
    public List<MemberInfo> members = new ArrayList<>();

    public WireMessage() {
    }

    public static WireMessage of(MessageType type) {
        WireMessage message = new WireMessage();
        message.type = type;
        return message;
    }
}

