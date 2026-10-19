package pl.edu.voice.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import pl.edu.voice.common.WireMessage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

final class ClientSession {
    final UUID id;
    final int senderTag;
    final byte[] relayToken;
    final Socket socket;
    final BufferedWriter writer;
    final ObjectMapper mapper;
    volatile String room;
    volatile String nickname;
    volatile String publicKey;
    volatile InetSocketAddress udpAddress;

    ClientSession(UUID id, int senderTag, byte[] relayToken, Socket socket,
                  BufferedWriter writer, ObjectMapper mapper) {
        this.id = id;
        this.senderTag = senderTag;
        this.relayToken = relayToken.clone();
        this.socket = socket;
        this.writer = writer;
        this.mapper = mapper;
    }

    synchronized void send(WireMessage message) throws IOException {
        writer.write(mapper.writeValueAsString(message));
        writer.newLine();
        writer.flush();
    }
}

