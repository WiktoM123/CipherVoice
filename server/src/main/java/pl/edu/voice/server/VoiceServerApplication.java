package pl.edu.voice.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(VoiceServerProperties.class)
public class VoiceServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(VoiceServerApplication.class, args);
    }
}

