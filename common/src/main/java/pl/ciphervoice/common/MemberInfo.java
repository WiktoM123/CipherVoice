package pl.ciphervoice.common;

public record MemberInfo(
        String clientId,
        String nickname,
        String publicKey,
        int senderTag
) {
}

