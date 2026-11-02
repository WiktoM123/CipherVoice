package pl.ciphervoice.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusControllerTest {
    @Test
    void reportsBoundPortsInsteadOfZeroFromConfiguration() throws Exception {
        RoomRegistry rooms = new RoomRegistry();
        VoiceServerProperties properties = new VoiceServerProperties(0, 0);
        ControlServer control = new ControlServer(properties, rooms, new ObjectMapper());
        UdpRelayServer relay = new UdpRelayServer(properties, rooms);
        try {
            control.start();
            relay.start();
            var status = new StatusController(rooms, control, relay).status();

            assertEquals("ok", status.get("status"));
            assertEquals(control.localPort(), status.get("controlPort"));
            assertEquals(relay.localPort(), status.get("udpPort"));
            assertTrue((Integer) status.get("controlPort") > 0);
            assertTrue((Integer) status.get("udpPort") > 0);
        } finally {
            control.stop();
            relay.stop();
        }
    }
}
