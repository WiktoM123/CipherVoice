package pl.ciphervoice.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pl.ciphervoice.common.Protocol;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

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

    @Test
    void respondsWithPongOnlyToAuthenticatedPingPackets() throws Exception {
        RoomRegistry registry = new RoomRegistry();
        UUID id = UUID.randomUUID();
        byte[] token = new byte[Protocol.RELAY_TOKEN_BYTES];
        token[0] = 7;
        token[15] = 42;
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
        String publicKey = Base64.getEncoder().encodeToString(kpg.generateKeyPair().getPublic().getEncoded());
        ClientSession session = new ClientSession(id, 1, token, new Socket(),
                new BufferedWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8)),
                new ObjectMapper());
        registry.join(session, "pokoj", "wiktor", publicKey);

        UdpRelayServer server = new UdpRelayServer(new VoiceServerProperties(0, 0), registry);
        server.start();
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(400);
            InetAddress localhost = InetAddress.getByName("127.0.0.1");
            long nonce = 123456789L;

            byte[] invalidToken = token.clone();
            invalidToken[0] ^= 0x01;
            byte[] badPing = ByteBuffer.allocate(Protocol.PING_DATAGRAM_BYTES)
                    .putInt(Protocol.PING_MAGIC)
                    .put(Protocol.VERSION)
                    .putLong(id.getMostSignificantBits())
                    .putLong(id.getLeastSignificantBits())
                    .put(invalidToken)
                    .putLong(nonce)
                    .array();
            client.send(new DatagramPacket(badPing, badPing.length, localhost, server.localPort()));
            assertThrows(SocketTimeoutException.class,
                    () -> client.receive(new DatagramPacket(new byte[64], 64)));

            byte[] validPing = ByteBuffer.allocate(Protocol.PING_DATAGRAM_BYTES)
                    .putInt(Protocol.PING_MAGIC)
                    .put(Protocol.VERSION)
                    .putLong(id.getMostSignificantBits())
                    .putLong(id.getLeastSignificantBits())
                    .put(token)
                    .putLong(nonce)
                    .array();
            client.send(new DatagramPacket(validPing, validPing.length, localhost, server.localPort()));

            byte[] reply = new byte[64];
            DatagramPacket pong = new DatagramPacket(reply, reply.length);
            client.receive(pong);
            assertEquals(Protocol.PONG_DATAGRAM_BYTES, pong.getLength());
            ByteBuffer in = ByteBuffer.wrap(pong.getData(), 0, pong.getLength());
            assertEquals(Protocol.PONG_MAGIC, in.getInt());
            assertEquals(Protocol.VERSION, in.get());
            assertEquals(nonce, in.getLong());
        } finally {
            server.stop();
        }
    }
}
