package pl.edu.voice.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voice")
public record VoiceServerProperties(int controlPort, int udpPort) {
    public VoiceServerProperties {
        requirePort("voice.control-port", controlPort);
        requirePort("voice.udp-port", udpPort);
        if (controlPort == udpPort) {
            throw new IllegalArgumentException(
                    "Port TCP i UDP muszą być różne");
        }
    }

    private static void requirePort(String name, int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(name + " musi być w zakresie 1–65535");
        }
    }
}
