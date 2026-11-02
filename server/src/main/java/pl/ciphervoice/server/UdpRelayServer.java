package pl.ciphervoice.server;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.ciphervoice.common.Protocol;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

@Component
public class UdpRelayServer {
    private static final Logger log = LoggerFactory.getLogger(UdpRelayServer.class);

    private final VoiceServerProperties properties;
    private final RoomRegistry rooms;
    private volatile DatagramSocket socket;
    private Thread receiver;
    private boolean stopping;

    public UdpRelayServer(VoiceServerProperties properties, RoomRegistry rooms) {
        this.properties = properties;
        this.rooms = rooms;
    }

    @PostConstruct
    synchronized void start() throws IOException {
        if (stopping || socket != null) {
            throw new IllegalStateException("Serwer UDP został już uruchomiony lub zatrzymany");
        }
        DatagramSocket relay = new DatagramSocket(properties.udpPort());
        socket = relay;
        receiver = Thread.ofVirtual().name("udp-relay").start(() -> receiveLoop(relay));
        log.info("Przekaźnik UDP działa na porcie {}", relay.getLocalPort());
    }

    @PreDestroy
    synchronized void stop() {
        stopping = true;
        if (socket != null) {
            socket.close();
        }
        if (receiver != null) {
            try {
                receiver.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    int localPort() {
        DatagramSocket relay = socket;
        return relay == null ? -1 : relay.getLocalPort();
    }

    private void receiveLoop(DatagramSocket relay) {
        try (relay) {
            byte[] buffer = new byte[Protocol.MAX_DATAGRAM_BYTES];
            while (!relay.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                relay.receive(packet);
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                process(relay, packet, data);
            }
        } catch (IOException exception) {
            if (socket == null || !socket.isClosed()) {
                log.error("Błąd przekaźnika UDP", exception);
            }
        }
    }

    private void process(DatagramSocket relay, DatagramPacket packet, byte[] data) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            int magic = input.readInt();
            if (magic == Protocol.REGISTER_MAGIC) {
                register(packet, input);
            } else if (magic == Protocol.AUDIO_MAGIC) {
                relayAudio(relay, packet, input, data);
            }
        } catch (Exception exception) {
            log.trace("Odrzucono nieprawidłowy datagram: {}", exception.getMessage());
        }
    }

    private void register(DatagramPacket packet, DataInputStream input) throws IOException {
        requireVersion(input.readByte());
        UUID id = new UUID(input.readLong(), input.readLong());
        byte[] token = input.readNBytes(Protocol.RELAY_TOKEN_BYTES);
        ClientSession client = rooms.find(id);
        if (client != null && MessageDigest.isEqual(client.relayToken, token)) {
            client.udpAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
        }
    }

    private void relayAudio(DatagramSocket relay, DatagramPacket incoming,
                            DataInputStream input, byte[] data) throws IOException {
        requireVersion(input.readByte());
        UUID id = new UUID(input.readLong(), input.readLong());
        int epoch = input.readInt();
        int senderTag = input.readInt();
        ClientSession sender = rooms.find(id);
        InetSocketAddress source = new InetSocketAddress(incoming.getAddress(), incoming.getPort());
        if (sender == null || sender.senderTag != senderTag || !source.equals(sender.udpAddress)
                || !rooms.isCurrentEpoch(sender, epoch)) {
            return;
        }
        for (ClientSession peer : rooms.peers(sender)) {
            InetSocketAddress target = peer.udpAddress;
            if (target != null) {
                relay.send(new DatagramPacket(data, data.length, target));
            }
        }
    }

    private static void requireVersion(byte version) {
        if (version != Protocol.VERSION) {
            throw new IllegalArgumentException("Nieobsługiwana wersja protokołu");
        }
    }
}
