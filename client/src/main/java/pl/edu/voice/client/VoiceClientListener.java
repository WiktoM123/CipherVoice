package pl.edu.voice.client;

import pl.edu.voice.common.MemberInfo;

import java.util.List;
import java.util.UUID;

interface VoiceClientListener {
    default void onStatus(String status) {
    }

    default void onRoomState(List<MemberInfo> members, UUID selfId, UUID leaderId, int epoch) {
    }

    default void onEncryptionChanged(boolean ready, int epoch) {
    }

    default void onLocalAudioLevel(double level) {
    }

    default void onError(String message) {
    }

    default void onDisconnected(String reason) {
    }
}
