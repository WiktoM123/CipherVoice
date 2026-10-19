package pl.edu.voice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import pl.edu.voice.common.MemberInfo;
import pl.edu.voice.common.MessageType;
import pl.edu.voice.common.Protocol;
import pl.edu.voice.common.WireMessage;

import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class VoiceClient implements AutoCloseable {
    private final String host;
    private final int controlPort;
    private final int udpPort;
    private final String room;
    private final String nickname;
    private final VoiceClientListener listener;
    private final ObjectMapper mapper = new ObjectMapper();
    private final KeyPair identity;
    private final Map<UUID, MemberInfo> members = new ConcurrentHashMap<>();
    private final Map<UUID, Long> receivedSequences = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private final CountDownLatch welcomed = new CountDownLatch(1);
    private final Object stateLock = new Object();
    private final AtomicBoolean disconnectNotified = new AtomicBoolean();

    private Socket controlSocket;
    private BufferedWriter controlWriter;
    private DatagramSocket udpSocket;
    private InetSocketAddress relayAddress;
    private OpusVoiceEngine audio;
    private volatile UUID clientId;
    private volatile UUID leaderId;
    private volatile int senderTag;
    private volatile int epoch;
    private volatile byte[] relayToken;
    private volatile SecretKeySpec groupKey;

    VoiceClient(String host, int controlPort, int udpPort, String room, String nickname,
                VoiceClientListener listener)
            throws Exception {
        this.host = host;
        this.controlPort = controlPort;
        this.udpPort = udpPort;
        this.room = room;
        this.nickname = nickname;
        this.listener = listener == null ? new VoiceClientListener() { } : listener;
        this.identity = CryptoSupport.newIdentityKeyPair();
    }

    void start() throws Exception {
        listener.onStatus("Łączenie z serwerem " + host + "…");
        controlSocket = new Socket();
        controlSocket.connect(new InetSocketAddress(host, controlPort), 5_000);
        controlSocket.setTcpNoDelay(true);
        controlWriter = new BufferedWriter(new OutputStreamWriter(
                controlSocket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                controlSocket.getInputStream(), StandardCharsets.UTF_8));
        running.set(true);

        WireMessage join = WireMessage.of(MessageType.JOIN);
        join.room = room;
        join.nickname = nickname;
        join.publicKey = CryptoSupport.encodePublicKey(identity.getPublic());
        sendControl(join);
        Thread.ofVirtual().name("control-receiver").start(() -> controlLoop(reader));

        if (!welcomed.await(10, TimeUnit.SECONDS)) {
            throw new IOException("Serwer nie odpowiedział na żądanie dołączenia");
        }
        if (!running.get() || clientId == null) {
            throw new IOException("Nie udało się dołączyć do pokoju");
        }

        udpSocket = new DatagramSocket();
        relayAddress = new InetSocketAddress(InetAddress.getByName(host), udpPort);
        udpSocket.connect(relayAddress);
        registerUdp();
        Thread.ofVirtual().name("udp-receiver").start(this::udpReceiveLoop);
        Thread.ofVirtual().name("udp-registration").start(this::registrationLoop);

        audio = new OpusVoiceEngine(this::sendAudio, listener::onLocalAudioLevel);
        try {
            audio.start();
            listener.onStatus("Połączono — mikrofon i głośniki są aktywne");
        } catch (Exception exception) {
            listener.onError("Połączono, ale nie można uruchomić audio: "
                    + exception.getMessage());
        }
    }

    private void controlLoop(BufferedReader reader) {
        try {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                handleControl(mapper.readValue(line, WireMessage.class));
            }
        } catch (Exception exception) {
            if (running.get()) {
                listener.onError("Utracono połączenie sterujące: " + exception.getMessage());
            }
        } finally {
            running.set(false);
            welcomed.countDown();
            notifyDisconnected("Połączenie z serwerem zostało zakończone");
        }
    }

    private void handleControl(WireMessage message) throws Exception {
        if (message.type == MessageType.ERROR) {
            listener.onError("Serwer odrzucił żądanie: " + message.error);
            running.set(false);
            welcomed.countDown();
            return;
        }
        if (message.type == MessageType.WELCOME) {
            clientId = UUID.fromString(message.clientId);
            senderTag = message.senderTag;
            relayToken = Base64.getDecoder().decode(message.relayToken);
            updateRoomState(message);
            listener.onStatus("Dołączono do pokoju " + room);
            welcomed.countDown();
        } else if (message.type == MessageType.ROOM_STATE) {
            updateRoomState(message);
        } else if (message.type == MessageType.REKEY_REQUEST) {
            updateRoomState(message);
            if (clientId != null && clientId.equals(leaderId)) {
                rotateAndDistributeKey(message.epoch);
            }
        } else if (message.type == MessageType.KEY_DISTRIBUTION) {
            acceptDistributedKey(message);
        }
        if (message.type == MessageType.WELCOME || message.type == MessageType.ROOM_STATE
                || message.type == MessageType.REKEY_REQUEST) {
            publishRoomState();
        }
    }

    private void updateRoomState(WireMessage message) {
        synchronized (stateLock) {
            int newEpoch = message.epoch == null ? epoch : message.epoch;
            if (newEpoch != epoch) {
                groupKey = null;
                sequence.set(0);
                receivedSequences.clear();
            }
            epoch = newEpoch;
            leaderId = message.leaderId == null ? null : UUID.fromString(message.leaderId);
            members.clear();
            if (message.members != null) {
                for (MemberInfo member : message.members) {
                    members.put(UUID.fromString(member.clientId()), member);
                }
            }
        }
    }

    private void rotateAndDistributeKey(int requestedEpoch) throws Exception {
        SecretKeySpec newKey;
        Map<UUID, MemberInfo> roster;
        synchronized (stateLock) {
            if (requestedEpoch != epoch || !clientId.equals(leaderId)) {
                return;
            }
            newKey = CryptoSupport.newGroupKey();
            groupKey = newKey;
            sequence.set(0);
            roster = Map.copyOf(members);
        }
        for (Map.Entry<UUID, MemberInfo> entry : roster.entrySet()) {
            UUID targetId = entry.getKey();
            if (targetId.equals(clientId)) {
                continue;
            }
            PublicKey targetKey = CryptoSupport.decodePublicKey(entry.getValue().publicKey());
            String context = keyContext(requestedEpoch, clientId, targetId);
            CryptoSupport.WrappedKey wrapped = CryptoSupport.wrapGroupKey(
                    newKey, identity.getPrivate(), targetKey, context);
            WireMessage distribution = WireMessage.of(MessageType.KEY_DISTRIBUTION);
            distribution.epoch = requestedEpoch;
            distribution.targetId = targetId.toString();
            distribution.wrapNonce = wrapped.nonce();
            distribution.wrappedKey = wrapped.ciphertext();
            sendControl(distribution);
        }
        listener.onEncryptionChanged(true, requestedEpoch);
    }

    private void acceptDistributedKey(WireMessage message) throws Exception {
        UUID senderId = UUID.fromString(message.clientId);
        UUID targetId = UUID.fromString(message.targetId);
        synchronized (stateLock) {
            if (!targetId.equals(clientId) || message.epoch == null || message.epoch != epoch
                    || !senderId.equals(leaderId)) {
                return;
            }
            MemberInfo sender = members.get(senderId);
            if (sender == null) {
                return;
            }
            PublicKey senderKey = CryptoSupport.decodePublicKey(sender.publicKey());
            String context = keyContext(epoch, senderId, clientId);
            groupKey = CryptoSupport.unwrapGroupKey(
                    new CryptoSupport.WrappedKey(message.wrapNonce, message.wrappedKey),
                    identity.getPrivate(), senderKey, context);
            sequence.set(0);
        }
        listener.onEncryptionChanged(true, epoch);
    }

    private void sendAudio(byte[] opus) {
        try {
            SecretKeySpec key = groupKey;
            DatagramSocket socket = udpSocket;
            if (!running.get() || key == null || socket == null || clientId == null) {
                return;
            }
            int packetEpoch = epoch;
            long packetSequence = sequence.getAndIncrement();
            byte[] encrypted = AudioPacketCodec.encrypt(clientId, packetEpoch, senderTag,
                    packetSequence, opus, key);
            socket.send(new DatagramPacket(encrypted, encrypted.length, relayAddress));
        } catch (Exception exception) {
            // Pojedynczy utracony datagram nie powinien przerywać rozmowy.
        }
    }

    private void udpReceiveLoop() {
        byte[] buffer = new byte[Protocol.MAX_DATAGRAM_BYTES];
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);
                byte[] data = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
                SecretKeySpec key = groupKey;
                if (key == null || AudioPacketCodec.peekEpoch(data) != epoch) {
                    continue;
                }
                AudioPacketCodec.DecodedPacket decoded = AudioPacketCodec.decrypt(data, key);
                MemberInfo member = members.get(decoded.senderId());
                if (member == null || member.senderTag() != decoded.senderTag()) {
                    continue;
                }
                Long previous = receivedSequences.put(decoded.senderId(), decoded.sequence());
                if (previous != null && decoded.sequence() <= previous) {
                    receivedSequences.put(decoded.senderId(), previous);
                    continue;
                }
                if (audio != null) {
                    audio.play(decoded.senderId(), decoded.opus());
                }
            } catch (Exception exception) {
                // Nieprawidłowy lub spóźniony datagram jest pomijany.
            }
        }
    }

    private void registrationLoop() {
        while (running.get()) {
            try {
                registerUdp();
                Thread.sleep(3_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException ignored) {
                // Następna próba nastąpi za trzy sekundy.
            }
        }
    }

    private void registerUdp() throws IOException {
        if (udpSocket == null || clientId == null || relayToken == null) {
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(Protocol.REGISTER_MAGIC);
            output.writeByte(Protocol.VERSION);
            output.writeLong(clientId.getMostSignificantBits());
            output.writeLong(clientId.getLeastSignificantBits());
            output.write(relayToken);
        }
        byte[] data = bytes.toByteArray();
        udpSocket.send(new DatagramPacket(data, data.length, relayAddress));
    }

    private synchronized void sendControl(WireMessage message) throws IOException {
        controlWriter.write(mapper.writeValueAsString(message));
        controlWriter.newLine();
        controlWriter.flush();
    }

    private String keyContext(int keyEpoch, UUID sender, UUID recipient) {
        return "voice-key/v1/" + room + "/" + keyEpoch + "/" + sender + "/" + recipient;
    }

    private void publishRoomState() {
        listener.onRoomState(members.values().stream()
                        .sorted(java.util.Comparator.comparing(MemberInfo::nickname,
                                String.CASE_INSENSITIVE_ORDER))
                        .toList(),
                clientId, leaderId, epoch);
        listener.onEncryptionChanged(groupKey != null, epoch);
    }

    void setMuted(boolean muted) {
        OpusVoiceEngine engine = audio;
        if (engine != null) {
            engine.setMuted(muted);
        }
    }

    void setDeafened(boolean deafened) {
        OpusVoiceEngine engine = audio;
        if (engine != null) {
            engine.setDeafened(deafened);
        }
    }

    boolean isRunning() {
        return running.get();
    }

    private void notifyDisconnected(String reason) {
        if (disconnectNotified.compareAndSet(false, true)) {
            listener.onDisconnected(reason);
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (audio != null) {
            audio.close();
        }
        if (udpSocket != null) {
            udpSocket.close();
        }
        try {
            if (controlSocket != null) {
                controlSocket.close();
            }
        } catch (IOException ignored) {
        }
        notifyDisconnected("Rozłączono");
    }
}

