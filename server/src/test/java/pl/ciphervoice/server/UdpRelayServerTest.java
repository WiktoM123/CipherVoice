package pl.ciphervoice.server;

import org.junit.jupiter.api.Test;

import java.net.BindException;
import java.net.DatagramSocket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class UdpRelayServerTest {
    @Test
    void failsStartupSynchronouslyWhenUdpPortIsOccupied() throws Exception {
        try (DatagramSocket occupied = new DatagramSocket(0)) {
            UdpRelayServer server = new UdpRelayServer(
                    new VoiceServerProperties(0, occupied.getLocalPort()), new RoomRegistry());
            try {
                assertThrows(BindException.class, server::start);
            } finally {
                server.stop();
            }
        }
    }

    @Test
    void stopsTheReceiverAndReleasesItsEphemeralPort() throws Exception {
        UdpRelayServer server = new UdpRelayServer(new VoiceServerProperties(0, 0), new RoomRegistry());
        server.start();
        int port = server.localPort();
        try {
            assertTrue(port > 0);
            assertTimeoutPreemptively(Duration.ofSeconds(2), server::stop);
            try (DatagramSocket rebound = new DatagramSocket(port)) {
                assertEquals(port, rebound.getLocalPort());
            }
        } finally {
            server.stop();
        }
    }
}
