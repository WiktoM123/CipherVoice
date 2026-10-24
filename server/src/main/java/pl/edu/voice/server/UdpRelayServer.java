package pl.edu.voice.server;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.edu.voice.common.Protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class UdpRelayServer {
    private static final Logger log = LoggerFactory.getLogger(UdpRelayServer.class);

    private final VoiceServerProperties properties;
    private final RoomRegistry rooms;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile DatagramSocket socket;

    public UdpRelayServer(VoiceServerProperties properties, RoomRegistry rooms) {
        this.properties = properties;
        this.rooms = rooms;
    }

    @PostConstruct
    void start() {
        Thread.ofPlatform().daemon(true).name("udp-relay").start(this::receiveLoop);
    }

    @PreDestroy
    void stop() {
        stopped.set(true);
        DatagramSocket relay = socket;
        socket = null;
        if (relay != null) {
            relay.close();
        }
    }

    private void receiveLoop() {
        try (DatagramSocket relay = new DatagramSocket(properties.udpPort())) {
            relay.setReceiveBufferSize(256 * 1024);
            socket = relay;
            if (stopped.get()) {
                return;
            }
            log.info("Przekaźnik UDP działa na porcie {}", properties.udpPort());
            byte[] buffer = new byte[Protocol.MAX_DATAGRAM_BYTES];
            while (!stopped.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                relay.receive(packet);
                process(relay, packet);
            }
        } catch (IOException exception) {
            if (!stopped.get()) {
                log.error("Błąd przekaźnika UDP", exception);
            }
        } finally {
            socket = null;
        }
    }

    private void process(DatagramSocket relay, DatagramPacket packet) {
        try {
            ByteBuffer input = ByteBuffer.wrap(
                    packet.getData(), packet.getOffset(), packet.getLength());
            int magic = input.getInt();
            if (magic == Protocol.REGISTER_MAGIC) {
                register(packet, input);
            } else if (magic == Protocol.AUDIO_MAGIC) {
                relayAudio(relay, packet, input);
            }
        } catch (Exception exception) {
            log.trace("Odrzucono nieprawidłowy datagram: {}", exception.getMessage());
        }
    }

    private void register(DatagramPacket packet, ByteBuffer input) {
        if (packet.getLength() != Protocol.REGISTER_DATAGRAM_BYTES
                || input.get() != Protocol.VERSION) {
            return;
        }
        UUID id = new UUID(input.getLong(), input.getLong());
        byte[] token = new byte[Protocol.RELAY_TOKEN_BYTES];
        input.get(token);
        ClientSession client = rooms.find(id);
        if (client != null && client.room != null
                && MessageDigest.isEqual(client.relayToken, token)) {
            client.udpAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
        }
    }

    private void relayAudio(DatagramSocket relay, DatagramPacket incoming, ByteBuffer input) {
        if (incoming.getLength() < Protocol.MIN_AUDIO_DATAGRAM_BYTES
                || input.get() != Protocol.VERSION) {
            return;
        }
        UUID id = new UUID(input.getLong(), input.getLong());
        int epoch = input.getInt();
        int senderTag = input.getInt();
        ClientSession sender = rooms.find(id);
        InetSocketAddress source = new InetSocketAddress(
                incoming.getAddress(), incoming.getPort());
        if (sender == null || sender.senderTag != senderTag
                || !source.equals(sender.udpAddress)
                || !rooms.isCurrentEpoch(sender, epoch)) {
            return;
        }
        for (ClientSession peer : rooms.peers(sender)) {
            InetSocketAddress target = peer.udpAddress;
            if (target == null) {
                continue;
            }
            try {
                relay.send(new DatagramPacket(incoming.getData(), incoming.getOffset(),
                        incoming.getLength(), target));
            } catch (IOException exception) {
                log.trace("Nie udało się przekazać pakietu do {}: {}",
                        peer.id, exception.getMessage());
            }
        }
    }
}
