package pl.edu.voice.common;

public record MemberInfo(
        String clientId,
        String nickname,
        String publicKey,
        int senderTag
) {
}

