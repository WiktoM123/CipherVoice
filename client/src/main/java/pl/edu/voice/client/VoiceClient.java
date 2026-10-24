package pl.edu.voice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import pl.edu.voice.common.JsonLines;
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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class VoiceClient implements AutoCloseable {
    private static final System.Logger log = System.getLogger(VoiceClient.class.getName());
    private static final int MAX_PENDING_ENVELOPES = 16;

    private final String host;
    private final int controlPort;
    private final int udpPort;
    private final String room;
    private final String nickname;
    private final boolean audioEnabled;
    private final VoiceClientListener listener;
    private final ObjectMapper mapper = new ObjectMapper();
    private final KeyPair identity;
    private final Map<UUID, MemberInfo> members = new ConcurrentHashMap<>();
    private final Map<UUID, Long> receivedSequences = new ConcurrentHashMap<>();
    private final Map<Integer, WireMessage> pendingEnvelopes = new LinkedHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private final AtomicBoolean userClosing = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean disconnectNotified = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private final CountDownLatch welcomed = new CountDownLatch(1);
    private final Object stateLock = new Object();

    private Socket controlSocket;
    private BufferedWriter controlWriter;
    private DatagramSocket udpSocket;
    private InetSocketAddress relayAddress;
    private volatile OpusVoiceEngine audio;
    private volatile UUID clientId;
    private volatile UUID leaderId;
    private volatile int senderTag;
    private volatile int epoch;
    private volatile byte[] relayToken;
    private volatile AudioKey audioKey;
    private volatile String lastServerError;

    VoiceClient(String host, int controlPort, int udpPort, String room, String nickname,
                VoiceClientListener listener) throws Exception {
        this(host, controlPort, udpPort, room, nickname, listener, true);
    }

    VoiceClient(String host, int controlPort, int udpPort, String room, String nickname,
                VoiceClientListener listener, boolean audioEnabled) throws Exception {
        this.host = host;
        this.controlPort = controlPort;
        this.udpPort = udpPort;
        this.room = room;
        this.nickname = nickname;
        this.audioEnabled = audioEnabled;
        this.listener = listener == null ? new VoiceClientListener() { } : listener;
        this.identity = CryptoSupport.newIdentityKeyPair();
    }

    void start() throws Exception {
        listener.onStatus("Łączenie z serwerem " + host + "…");
        controlSocket = new Socket();
        controlSocket.connect(new InetSocketAddress(host, controlPort), 5_000);
        controlSocket.setTcpNoDelay(true);
        controlSocket.setKeepAlive(true);
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
            throw new IOException(lastServerError == null
                    ? "Nie udało się dołączyć do pokoju" : lastServerError);
        }

        udpSocket = new DatagramSocket();
        udpSocket.setReceiveBufferSize(64 * 1024);
        relayAddress = new InetSocketAddress(controlSocket.getInetAddress(), udpPort);
        udpSocket.connect(relayAddress);
        if (!running.get()) {
            throw new IOException("Połączenie zostało przerwane podczas dołączania");
        }
        registerUdp();
        Thread.ofVirtual().name("udp-receiver").start(this::udpReceiveLoop);
        Thread.ofVirtual().name("udp-registration").start(this::registrationLoop);

        if (audioEnabled) {
            OpusVoiceEngine engine = new OpusVoiceEngine(this::sendAudio,
                    listener::onLocalAudioLevel, listener::onParticipantAudioLevel);
            audio = engine;
            try {
                engine.start();
                listener.onStatus("Połączono — mikrofon i głośniki są aktywne");
            } catch (Exception exception) {
                listener.onError("Połączono, ale nie można uruchomić audio: "
                        + exception.getMessage());
            }
        } else {
            listener.onStatus("Połączono (audio wyłączone)");
        }

        if (!running.get()) {
            throw new IOException("Połączenie zostało przerwane podczas uruchamiania audio");
        }
        started.set(true);
    }

    private void controlLoop(BufferedReader reader) {
        try {
            String line;
            while (running.get()
                    && (line = JsonLines.read(reader, Protocol.MAX_CONTROL_LINE_CHARS)) != null) {
                try {
                    handleControl(mapper.readValue(line, WireMessage.class));
                } catch (Exception exception) {
                    lastServerError = "Nieprawidłowy komunikat serwera: "
                            + messageOf(exception);
                    if (!started.get()) {
                        running.set(false);
                    }
                    welcomed.countDown();
                    listener.onError(lastServerError);
                }
            }
        } catch (Exception exception) {
            if (running.get() && !userClosing.get()) {
                lastServerError = "Utracono połączenie sterujące: " + messageOf(exception);
                listener.onError(lastServerError);
            }
        } finally {
            boolean notify = !userClosing.get() && started.get();
            releaseResources();
            welcomed.countDown();
            if (notify) {
                notifyDisconnected("Połączenie z serwerem zostało zakończone");
            }
        }
    }

    private void handleControl(WireMessage message) throws Exception {
        if (message == null || message.type == null) {
            throw new IllegalArgumentException("Brak typu komunikatu");
        }
        switch (message.type) {
            case ERROR -> {
                lastServerError = "Serwer odrzucił żądanie: "
                        + (message.error == null ? "nieznany błąd" : message.error);
                listener.onError(lastServerError);
                running.set(false);
                welcomed.countDown();
            }
            case WELCOME -> handleWelcome(message);
            case ROOM_STATE, REKEY_REQUEST -> refreshRoomState(message);
            case KEY_DISTRIBUTION -> acceptDistributedKey(message);
            case JOIN -> throw new IllegalArgumentException("Nieoczekiwany komunikat JOIN");
        }
    }

    private void refreshRoomState(WireMessage message) {
        updateRoomState(message);
        applyPendingEnvelope();
        ensureLeaderKey();
        publishRoomState();
    }

    private void handleWelcome(WireMessage message) throws IOException {
        if (message.clientId == null || message.senderTag == null
                || message.relayToken == null || message.epoch == null) {
            throw new IOException("Niepełne dane powitalne serwera");
        }
        UUID newClientId;
        try {
            newClientId = UUID.fromString(message.clientId);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Nieprawidłowy identyfikator klienta", exception);
        }
        byte[] token;
        try {
            token = Base64.getDecoder().decode(message.relayToken);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Nieprawidłowy token UDP", exception);
        }
        if (token.length != Protocol.RELAY_TOKEN_BYTES) {
            throw new IOException("Nieprawidłowa długość tokenu UDP");
        }

        clientId = newClientId;
        senderTag = message.senderTag;
        relayToken = token;
        updateRoomState(message);
        applyPendingEnvelope();
        ensureLeaderKey();
        publishRoomState();
        listener.onStatus("Dołączono do pokoju " + room);
        welcomed.countDown();
    }

    private void updateRoomState(WireMessage message) {
        int newEpoch = message.epoch == null ? epoch : message.epoch;
        if (newEpoch < epoch) {
            return; // late broadcast from an older membership change
        }

        UUID newLeader = parseOptionalUuid(message.leaderId, "leaderId");
        Map<UUID, MemberInfo> newMembers = new HashMap<>();
        if (message.members != null) {
            for (MemberInfo member : message.members) {
                if (member == null || member.clientId() == null) {
                    throw new IllegalArgumentException("Niepełne dane uczestnika");
                }
                UUID memberId = parseUuid(member.clientId(), "clientId uczestnika");
                newMembers.put(memberId, member);
            }
        }

        Set<UUID> removed = new HashSet<>();
        synchronized (stateLock) {
            if (newEpoch < epoch) {
                return;
            }
            if (newEpoch > epoch) {
                epoch = newEpoch;
                audioKey = null;
                receivedSequences.clear();
            }
            leaderId = newLeader;
            removed.addAll(members.keySet());
            members.clear();
            members.putAll(newMembers);
            removed.removeAll(newMembers.keySet());
        }
        OpusVoiceEngine engine = audio;
        if (engine != null) {
            removed.forEach(engine::removeParticipant);
        }
    }

    private void rotateAndDistributeKey(int requestedEpoch) {
        SecretKeySpec newKey;
        Map<UUID, MemberInfo> roster;
        synchronized (stateLock) {
            if (requestedEpoch != epoch || clientId == null || !clientId.equals(leaderId)) {
                return;
            }
            AudioKey current = audioKey;
            if (current != null && current.epoch() == requestedEpoch) {
                return; // duplicate REKEY_REQUEST for an epoch that already has a key
            }
            newKey = CryptoSupport.newGroupKey();
            audioKey = new AudioKey(requestedEpoch, newKey);
            roster = Map.copyOf(members);
        }

        List<String> failed = new ArrayList<>();
        for (Map.Entry<UUID, MemberInfo> entry : roster.entrySet()) {
            UUID targetId = entry.getKey();
            if (targetId.equals(clientId)) {
                continue;
            }
            try {
                PublicKey targetKey = CryptoSupport.decodePublicKey(
                        entry.getValue().publicKey());
                String context = keyContext(requestedEpoch, clientId, targetId);
                CryptoSupport.WrappedKey wrapped = CryptoSupport.wrapGroupKey(
                        newKey, identity.getPrivate(), targetKey, context);
                WireMessage distribution = WireMessage.of(MessageType.KEY_DISTRIBUTION);
                distribution.epoch = requestedEpoch;
                distribution.targetId = targetId.toString();
                distribution.wrapNonce = wrapped.nonce();
                distribution.wrappedKey = wrapped.ciphertext();
                sendControl(distribution);
            } catch (Exception exception) {
                failed.add(entry.getValue().nickname() == null
                        ? targetId.toString() : entry.getValue().nickname());
            }
        }
        listener.onEncryptionChanged(true, requestedEpoch);
        if (!failed.isEmpty()) {
            listener.onError("Nie udało się przekazać klucza do: "
                    + String.join(", ", failed));
        }
    }

    private void acceptDistributedKey(WireMessage message) {
        if (message.clientId == null || message.targetId == null || message.epoch == null
                || message.wrapNonce == null || message.wrappedKey == null) {
            listener.onError("Serwer przesłał niepełną kopertę klucza");
            return;
        }
        if (tryAcceptKey(message)) {
            return;
        }
        rememberPendingEnvelope(message);
    }

    private boolean tryAcceptKey(WireMessage message) {
        UUID senderId;
        UUID targetId;
        try {
            senderId = parseUuid(message.clientId, "clientId nadawcy klucza");
            targetId = parseUuid(message.targetId, "targetId koperty");
        } catch (IllegalArgumentException exception) {
            listener.onError("Nieprawidłowa koperta klucza: " + messageOf(exception));
            return false;
        }

        SecretKeySpec acceptedKey = null;
        String failure = null;
        int acceptedEpoch;
        synchronized (stateLock) {
            acceptedEpoch = epoch;
            if (clientId == null || !targetId.equals(clientId)
                    || message.epoch != acceptedEpoch
                    || !senderId.equals(leaderId) || senderId.equals(clientId)) {
                return false;
            }
            MemberInfo sender = members.get(senderId);
            if (sender == null) {
                return false;
            }
            try {
                PublicKey senderKey = CryptoSupport.decodePublicKey(sender.publicKey());
                String context = keyContext(acceptedEpoch, senderId, clientId);
                acceptedKey = CryptoSupport.unwrapGroupKey(
                        new CryptoSupport.WrappedKey(message.wrapNonce, message.wrappedKey),
                        identity.getPrivate(), senderKey, context);
            } catch (GeneralSecurityException | IllegalArgumentException exception) {
                failure = messageOf(exception);
            }
            if (acceptedKey != null) {
                audioKey = new AudioKey(acceptedEpoch, acceptedKey);
            }
        }
        if (failure != null) {
            listener.onError("Nie udało się odszyfrować klucza grupowego: " + failure);
            return false;
        }
        if (acceptedKey == null) {
            return false;
        }
        listener.onEncryptionChanged(true, acceptedEpoch);
        return true;
    }

    /**
     * An envelope can arrive before this client processed the room state that
     * announces its epoch, for example during two concurrent membership
     * changes. Such an envelope is kept until the matching room state arrives.
     */
    private void rememberPendingEnvelope(WireMessage message) {
        synchronized (stateLock) {
            if (message.epoch == null || message.targetId == null || message.epoch < epoch) {
                return;
            }
            if (clientId != null) {
                if (!message.targetId.equals(clientId.toString()) || message.epoch <= epoch) {
                    return;
                }
            }
            pendingEnvelopes.put(message.epoch, message);
            while (pendingEnvelopes.size() > MAX_PENDING_ENVELOPES) {
                Integer oldest = pendingEnvelopes.keySet().iterator().next();
                pendingEnvelopes.remove(oldest);
            }
        }
    }

    private void applyPendingEnvelope() {
        WireMessage candidate;
        synchronized (stateLock) {
            if (clientId == null) {
                return; // wait for WELCOME, which carries our target id
            }
            pendingEnvelopes.keySet().removeIf(pendingEpoch -> pendingEpoch < epoch);
            candidate = pendingEnvelopes.remove(epoch);
        }
        if (candidate != null) {
            tryAcceptKey(candidate);
        }
    }

    /**
     * When a membership change makes this client the leader, nobody else is
     * going to generate the new group key. This also covers a REKEY_REQUEST
     * that arrived before the room state updating the leader.
     */
    private void ensureLeaderKey() {
        int requestedEpoch;
        synchronized (stateLock) {
            if (clientId == null || !clientId.equals(leaderId)
                    || (audioKey != null && audioKey.epoch() == epoch)) {
                return;
            }
            requestedEpoch = epoch;
        }
        rotateAndDistributeKey(requestedEpoch);
    }

    private void sendAudio(byte[] opus) {
        try {
            AudioKey currentKey = audioKey;
            DatagramSocket socket = udpSocket;
            UUID sender = clientId;
            if (!running.get() || currentKey == null || socket == null
                    || sender == null || relayAddress == null || opus == null) {
                return;
            }
            long packetSequence = sequence.getAndIncrement();
            byte[] encrypted = AudioPacketCodec.encrypt(sender, currentKey.epoch(), senderTag,
                    packetSequence, opus, currentKey.key());
            socket.send(new DatagramPacket(encrypted, encrypted.length, relayAddress));
        } catch (Exception exception) {
            // A single lost datagram must not interrupt the conversation.
        }
    }

    private void udpReceiveLoop() {
        byte[] buffer = new byte[Protocol.MAX_DATAGRAM_BYTES];
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);
                byte[] data = java.util.Arrays.copyOf(packet.getData(), packet.getLength());

                AudioKey currentKey = audioKey;
                if (currentKey == null || AudioPacketCodec.peekEpoch(data) != currentKey.epoch()
                        || audioKey != currentKey) {
                    continue;
                }
                AudioPacketCodec.DecodedPacket decoded = AudioPacketCodec.decrypt(
                        data, currentKey.key());
                MemberInfo member = members.get(decoded.senderId());
                if (member == null || member.senderTag() != decoded.senderTag()) {
                    continue;
                }
                Long previous = receivedSequences.get(decoded.senderId());
                if (previous != null && decoded.sequence() <= previous) {
                    continue;
                }
                receivedSequences.put(decoded.senderId(), decoded.sequence());
                OpusVoiceEngine engine = audio;
                if (engine != null) {
                    engine.play(decoded.senderId(), decoded.opus());
                }
            } catch (Exception exception) {
                // Invalid, replayed or late datagrams are simply dropped.
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
            } catch (IOException exception) {
                // Try again on the next iteration.
            }
        }
    }

    private void registerUdp() throws IOException {
        DatagramSocket socket = udpSocket;
        UUID id = clientId;
        byte[] token = relayToken;
        InetSocketAddress relay = relayAddress;
        if (socket == null || id == null || token == null || relay == null) {
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(Protocol.REGISTER_MAGIC);
            output.writeByte(Protocol.VERSION);
            output.writeLong(id.getMostSignificantBits());
            output.writeLong(id.getLeastSignificantBits());
            output.write(token);
        }
        byte[] data = bytes.toByteArray();
        socket.send(new DatagramPacket(data, data.length, relay));
    }

    private synchronized void sendControl(WireMessage message) throws IOException {
        BufferedWriter writer = controlWriter;
        if (writer == null) {
            throw new IOException("Kanał sterujący nie jest połączony");
        }
        JsonLines.write(writer, mapper.writeValueAsString(message));
    }

    private String keyContext(int keyEpoch, UUID sender, UUID recipient) {
        return "voice-key/v2/" + room + "/" + keyEpoch + "/" + sender + "/" + recipient;
    }

    private void publishRoomState() {
        List<MemberInfo> snapshot;
        UUID self;
        UUID leader;
        int currentEpoch;
        boolean ready;
        synchronized (stateLock) {
            snapshot = members.values().stream()
                    .sorted(Comparator.comparing(MemberInfo::nickname,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .toList();
            self = clientId;
            leader = leaderId;
            currentEpoch = epoch;
            ready = audioKey != null && audioKey.epoch() == currentEpoch;
        }
        listener.onRoomState(snapshot, self, leader, currentEpoch);
        listener.onEncryptionChanged(ready, currentEpoch);
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
        userClosing.set(true);
        releaseResources();
        welcomed.countDown();
        if (started.get()) {
            notifyDisconnected("Rozłączono");
        }
    }

    private void releaseResources() {
        running.set(false);
        if (!resourcesClosed.compareAndSet(false, true)) {
            return;
        }
        OpusVoiceEngine engine = audio;
        audio = null;
        if (engine != null) {
            engine.close();
        }
        DatagramSocket socket = udpSocket;
        udpSocket = null;
        if (socket != null) {
            socket.close();
        }
        Socket control = controlSocket;
        controlSocket = null;
        if (control != null) {
            try {
                control.close();
            } catch (IOException ignored) {
                // The socket may already be closed by the peer.
            }
        }
    }

    private static UUID parseOptionalUuid(String value, String field) {
        return value == null ? null : parseUuid(value, field);
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Nieprawidłowy " + field, exception);
        }
    }

    private static String messageOf(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private record AudioKey(int epoch, SecretKeySpec key) {
    }
}
