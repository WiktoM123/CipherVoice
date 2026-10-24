package pl.edu.voice.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pl.edu.voice.common.Protocol;
import pl.edu.voice.common.PublicKeys;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class UdpRelayServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger TAGS = new AtomicInteger(100);

    @Test
    void registeredParticipantsExchangeOpaqueDatagrams() throws Exception {
        int udpPort = freeUdpPort();
        VoiceServerProperties properties = new VoiceServerProperties(freeTcpPort(), udpPort);
        RoomRegistry rooms = new RoomRegistry();
        UdpRelayServer relay = new UdpRelayServer(properties, rooms);
        try {
            ClientSession alice = session();
            ClientSession bob = session();
            rooms.join(alice, "pokoj", "Alice", publicKey());
            RoomRegistry.Change change = rooms.join(bob, "pokoj", "Bob", publicKey());

            relay.start();
            Thread.sleep(200);

            try (DatagramSocket aliceSocket = new DatagramSocket();
                 DatagramSocket bobSocket = new DatagramSocket()) {
                bobSocket.setSoTimeout(2_000);
                for (int i = 0; i < 5; i++) {
                    sendRegistration(aliceSocket, udpPort, alice);
                    sendRegistration(bobSocket, udpPort, bob);
                    Thread.sleep(20);
                }

                byte[] audio = audioPacket(alice, change.epoch());
                aliceSocket.send(new DatagramPacket(audio, audio.length,
                        new InetSocketAddress("127.0.0.1", udpPort)));

                DatagramPacket received = new DatagramPacket(
                        new byte[Protocol.MAX_DATAGRAM_BYTES],
                        Protocol.MAX_DATAGRAM_BYTES);
                bobSocket.receive(received);
                assertArrayEquals(audio, Arrays.copyOf(
                        received.getData(), received.getLength()));
            }
        } finally {
            relay.stop();
        }
    }

    private static void sendRegistration(DatagramSocket socket, int port,
                                         ClientSession session) throws Exception {
        byte[] data = ByteBuffer.allocate(Protocol.REGISTER_DATAGRAM_BYTES)
                .putInt(Protocol.REGISTER_MAGIC)
                .put(Protocol.VERSION)
                .putLong(session.id.getMostSignificantBits())
                .putLong(session.id.getLeastSignificantBits())
                .put(session.relayToken)
                .array();
        socket.send(new DatagramPacket(data, data.length,
                new InetSocketAddress("127.0.0.1", port)));
    }

    private static byte[] audioPacket(ClientSession sender, int epoch) {
        ByteBuffer packet = ByteBuffer.allocate(Protocol.MIN_AUDIO_DATAGRAM_BYTES);
        packet.putInt(Protocol.AUDIO_MAGIC)
                .put(Protocol.VERSION)
                .putLong(sender.id.getMostSignificantBits())
                .putLong(sender.id.getLeastSignificantBits())
                .putInt(epoch)
                .putInt(sender.senderTag)
                .putLong(1)
                .putShort((short) Protocol.GCM_TAG_BYTES)
                .put(new byte[Protocol.GCM_TAG_BYTES]);
        return packet.array();
    }

    private static ClientSession session() {
        return new ClientSession(UUID.randomUUID(), TAGS.getAndIncrement(),
                new byte[Protocol.RELAY_TOKEN_BYTES], new Socket(),
                new BufferedWriter(new StringWriter()), MAPPER);
    }

    private static String publicKey() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        return PublicKeys.encode(pair.getPublic());
    }

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int freeTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
