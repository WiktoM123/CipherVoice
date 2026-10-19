package pl.edu.voice.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voice")
public record VoiceServerProperties(int controlPort, int udpPort) {
}

