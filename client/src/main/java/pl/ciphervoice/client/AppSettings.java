package pl.ciphervoice.client;

import javafx.scene.input.KeyCode;

import java.util.prefs.Preferences;

/**
 * Persists user connection, audio and Push-to-Talk preferences across launches.
 */
final class AppSettings {
    private static final String KEY_SERVER_HOST = "serverHost";
    private static final String KEY_ROOM_NAME = "roomName";
    private static final String KEY_NICKNAME = "nickname";
    private static final String KEY_CONTROL_PORT = "controlPort";
    private static final String KEY_UDP_PORT = "udpPort";
    private static final String KEY_MICROPHONE_NAME = "microphoneName";
    private static final String KEY_SPEAKER_NAME = "speakerName";
    private static final String KEY_MICROPHONE_GAIN = "microphoneGain";
    private static final String KEY_NOISE_GATE = "noiseGateThreshold";
    private static final String KEY_MASTER_VOLUME = "masterVolume";
    private static final String KEY_PTT_ENABLED = "pttEnabled";
    private static final String KEY_PTT_KEY = "pttKey";
    private static final String KEY_SYSTEM_SOUNDS = "systemSoundsEnabled";

    private final Preferences prefs;

    AppSettings() {
        this(Preferences.userNodeForPackage(AppSettings.class));
    }

    AppSettings(Preferences prefs) {
        this.prefs = prefs;
    }

    String serverHost() {
        return prefs.get(KEY_SERVER_HOST, "localhost");
    }

    void setServerHost(String value) {
        prefs.put(KEY_SERVER_HOST, value == null || value.isBlank() ? "localhost" : value.trim());
    }

    String roomName() {
        return prefs.get(KEY_ROOM_NAME, "pokoj");
    }

    void setRoomName(String value) {
        prefs.put(KEY_ROOM_NAME, value == null || value.isBlank() ? "pokoj" : value.trim());
    }

    String nickname(String fallback) {
        String stored = prefs.get(KEY_NICKNAME, "");
        return stored.isBlank() ? fallback : stored;
    }

    void setNickname(String value) {
        if (value != null && !value.isBlank()) {
            prefs.put(KEY_NICKNAME, value.trim());
        }
    }

    int controlPort() {
        return clampPort(prefs.getInt(KEY_CONTROL_PORT, 8090), 8090);
    }

    void setControlPort(int port) {
        prefs.putInt(KEY_CONTROL_PORT, clampPort(port, 8090));
    }

    int udpPort() {
        return clampPort(prefs.getInt(KEY_UDP_PORT, 8091), 8091);
    }

    void setUdpPort(int port) {
        prefs.putInt(KEY_UDP_PORT, clampPort(port, 8091));
    }

    String microphoneName() {
        String value = prefs.get(KEY_MICROPHONE_NAME, "");
        return value.isBlank() ? null : value;
    }

    void setMicrophoneName(String name) {
        if (name == null || name.isBlank()) {
            prefs.remove(KEY_MICROPHONE_NAME);
        } else {
            prefs.put(KEY_MICROPHONE_NAME, name);
        }
    }

    String speakerName() {
        String value = prefs.get(KEY_SPEAKER_NAME, "");
        return value.isBlank() ? null : value;
    }

    void setSpeakerName(String name) {
        if (name == null || name.isBlank()) {
            prefs.remove(KEY_SPEAKER_NAME);
        } else {
            prefs.put(KEY_SPEAKER_NAME, name);
        }
    }

    double microphoneGain() {
        return clampDouble(prefs.getDouble(KEY_MICROPHONE_GAIN, 1.0), 0.25, 3.0);
    }

    void setMicrophoneGain(double gain) {
        prefs.putDouble(KEY_MICROPHONE_GAIN, clampDouble(gain, 0.25, 3.0));
    }

    double noiseGateThreshold() {
        return clampDouble(prefs.getDouble(KEY_NOISE_GATE, 0.03), 0.0, 0.40);
    }

    void setNoiseGateThreshold(double threshold) {
        prefs.putDouble(KEY_NOISE_GATE, clampDouble(threshold, 0.0, 0.40));
    }

    double masterVolume() {
        return clampDouble(prefs.getDouble(KEY_MASTER_VOLUME, 1.0), 0.0, 2.0);
    }

    void setMasterVolume(double volume) {
        prefs.putDouble(KEY_MASTER_VOLUME, clampDouble(volume, 0.0, 2.0));
    }

    boolean pttEnabled() {
        return prefs.getBoolean(KEY_PTT_ENABLED, false);
    }

    void setPttEnabled(boolean enabled) {
        prefs.putBoolean(KEY_PTT_ENABLED, enabled);
    }

    KeyCode pttKey() {
        String raw = prefs.get(KEY_PTT_KEY, KeyCode.V.name());
        try {
            return KeyCode.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            return KeyCode.V;
        }
    }

    void setPttKey(KeyCode code) {
        prefs.put(KEY_PTT_KEY, (code == null ? KeyCode.V : code).name());
    }

    boolean systemSoundsEnabled() {
        return prefs.getBoolean(KEY_SYSTEM_SOUNDS, true);
    }

    void setSystemSoundsEnabled(boolean enabled) {
        prefs.putBoolean(KEY_SYSTEM_SOUNDS, enabled);
    }

    private static int clampPort(int port, int fallback) {
        return port >= 1 && port <= 65_535 ? port : fallback;
    }

    private static double clampDouble(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
