package pl.ciphervoice.server;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class StatusController {
    private final RoomRegistry rooms;
    private final ControlServer control;
    private final UdpRelayServer relay;

    public StatusController(RoomRegistry rooms, ControlServer control, UdpRelayServer relay) {
        this.rooms = rooms;
        this.control = control;
        this.relay = relay;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "status", "ok",
                "rooms", rooms.roomCount(),
                "participants", rooms.participantCount(),
                "controlPort", control.localPort(),
                "udpPort", relay.localPort()
        );
    }
}

