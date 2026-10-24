package pl.edu.voice.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Starts the real TCP control server and UDP relay on ephemeral test ports. */
public final class TestVoiceServer implements AutoCloseable {
    private final int controlPort;
    private final int udpPort;
    private final ControlServer control;
    private final UdpRelayServer udp;

    public TestVoiceServer(int controlPort, int udpPort) {
        this.controlPort = controlPort;
        this.udpPort = udpPort;
        VoiceServerProperties properties = new VoiceServerProperties(controlPort, udpPort);
        RoomRegistry rooms = new RoomRegistry();
        ObjectMapper mapper = new ObjectMapper();
        this.control = new ControlServer(properties, rooms, mapper);
        this.udp = new UdpRelayServer(properties, rooms);
    }

    public void start() throws Exception {
        control.start();
        udp.start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", controlPort), 200);
                Thread.sleep(50);
                return;
            } catch (IOException exception) {
                Thread.sleep(25);
            }
        }
        throw new IllegalStateException("Serwer testowy nie uruchomił się w ciągu 5 sekund");
    }

    public int controlPort() {
        return controlPort;
    }

    public int udpPort() {
        return udpPort;
    }

    @Override
    public void close() {
        udp.stop();
        control.stop();
    }
}
