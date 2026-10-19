package pl.edu.voice.server;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class StatusController {
    private final RoomRegistry rooms;
    private final VoiceServerProperties properties;

    public StatusController(RoomRegistry rooms, VoiceServerProperties properties) {
        this.rooms = rooms;
        this.properties = properties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "status", "ok",
                "rooms", rooms.roomCount(),
                "participants", rooms.participantCount(),
                "controlPort", properties.controlPort(),
                "udpPort", properties.udpPort()
        );
    }
}

