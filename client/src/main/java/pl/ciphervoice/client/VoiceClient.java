package pl.ciphervoice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import pl.ciphervoice.common.JsonLines;
import pl.ciphervoice.common.MemberInfo;
import pl.ciphervoice.common.MessageType;
import pl.ciphervoice.common.Protocol;
import pl.ciphervoice.common.WireMessage;

import javax.crypto.spec.SecretKeySpec;
import javax.sound.sampled.Mixer;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

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
    private final Map<Integer, WireMessage> pendingEnvelopes = new LinkedHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean userClosing = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean disconnectNotified = new AtomicBoolean();
    private final CountDownLatch welcomed = new CountDownLatch(1);
    private final Object stateLock = new Object();
    private final Object lifecycleLock = new Object();
    private final Object pingLock = new Object();
    private final Mixer.Info microphoneMixer;
    private final Mixer.Info speakerMixer;
    private final Map<UUID, Double> participantVolumes = new ConcurrentHashMap<>();

    private volatile Socket controlSocket;
    private BufferedWriter controlWriter;
    private volatile DatagramSocket udpSocket;
    private InetSocketAddress relayAddress;
    private volatile OpusVoiceEngine audio;
    private volatile Thread registrationThread;
    private volatile UUID clientId;
    private volatile UUID leaderId;
    private volatile int senderTag;
    private volatile int epoch;
    private volatile byte[] relayToken;
    private volatile EpochCryptoContext cryptoContext;
    private volatile SasSnapshot sas = SasSnapshot.unavailable(
            "SAS pojawi się po uzgodnieniu klucza grupowego");
    /** Local confirmation only; guarded by {@link #stateLock}. */
    private String verifiedSasCode;
    private int verifiedSasEpoch = -1;
    private volatile String lastServerError;
    private volatile double microphoneGain = 1.0;
    private volatile double noiseGateThreshold = 0.0;
    private volatile double masterVolume = 1.0;
    private volatile boolean manualMuted;
    private volatile boolean pttEnabled;
    private volatile boolean pttPressed;
    private volatile boolean deafened;
    private final String identityFingerprint;
    private volatile PacketPreview lastOutgoingPacket;
    private volatile PacketPreview lastIncomingPacket;
    private long pendingPingNonce;
    private long pendingPingSentNanos;
    private boolean hasPendingPing;

    VoiceClient(String host, int controlPort, int udpPort, String room, String nickname,
                VoiceClientListener listener) throws Exception {
        this(host, controlPort, udpPort, room, nickname, listener, true);
    }

    VoiceClient(String host, int controlPort, int udpPort, String room, String nickname,
                VoiceClientListener listener, boolean audioEnabled) throws Exception {
        this(host, controlPort, udpPort, room, nickname, listener, audioEnabled, null, null);
    }

    VoiceClient(String host, int controlPort, int udpPort, String room, String nickname,
                VoiceClientListener listener, Mixer.Info microphoneMixer, Mixer.Info speakerMixer)
            throws Exception {
        this(host, controlPort, udpPort, room, nickname, listener, true,
                microphoneMixer, speakerMixer);
    }

    private VoiceClient(String host, int controlPort, int udpPort, String room, String nickname,
                        VoiceClientListener listener, boolean audioEnabled,
                        Mixer.Info microphoneMixer, Mixer.Info speakerMixer) throws Exception {
        this.host = host;
        this.controlPort = controlPort;
        this.udpPort = udpPort;
        this.room = room;
        this.nickname = nickname;
        this.audioEnabled = audioEnabled;
        this.listener = listener == null ? new VoiceClientListener() { } : listener;
        this.identity = CryptoSupport.newIdentityKeyPair();
        this.identityFingerprint = CryptoSupport.fingerprintHex(identity.getPublic().getEncoded(), 12);
        this.microphoneMixer = microphoneMixer;
        this.speakerMixer = speakerMixer;
    }

    void start() throws Exception {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Klient został już uruchomiony");
        }
        try {
            startConnection();
        } catch (Exception | Error exception) {
            shutdown("Nie udało się uruchomić połączenia");
            throw exception;
        }
    }

    private void startConnection() throws Exception {
        listener.onStatus("Łączenie z serwerem " + host + "…");
        synchronized (lifecycleLock) {
            requireOpen();
            controlSocket = new Socket();
        }
        controlSocket.connect(new InetSocketAddress(host, controlPort), 5_000);
        controlSocket.setTcpNoDelay(true);
        controlSocket.setKeepAlive(true);
        BufferedReader reader;
        synchronized (lifecycleLock) {
            requireOpen();
            controlWriter = new BufferedWriter(new OutputStreamWriter(
                    controlSocket.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(
                    controlSocket.getInputStream(), StandardCharsets.UTF_8));
            running.set(true);
        }

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

        synchronized (lifecycleLock) {
            requireOpen();
            udpSocket = new DatagramSocket();
            udpSocket.setReceiveBufferSize(64 * 1024);
            relayAddress = new InetSocketAddress(host, udpPort);
            udpSocket.connect(relayAddress);
            registerUdp();
            Thread.ofVirtual().name("udp-receiver").start(this::udpReceiveLoop);
            registrationThread = Thread.ofVirtual().name("udp-registration")
                    .start(this::registrationLoop);

            if (audioEnabled) {
                OpusVoiceEngine engine = new OpusVoiceEngine(this::sendAudio,
                        listener::onLocalAudioLevel, listener::onParticipantAudioLevel,
                        listener::onError, microphoneMixer, speakerMixer);
                applyAudioConfig(engine);
                audio = engine;
                try {
                    engine.start();
                    listener.onStatus("Połączono — mikrofon i głośniki są aktywne");
                } catch (Exception exception) {
                    engine.close();
                    audio = null;
                    listener.onError("Połączono, ale nie można uruchomić audio: "
                            + exception.getMessage());
                }
            } else {
                listener.onStatus("Połączono (audio wyłączone)");
            }
        }

        if (!running.get()) {
            throw new IOException("Połączenie zostało przerwane podczas uruchamiania audio");
        }
    }

    private void requireOpen() throws IOException {
        if (closed.get()) {
            throw new IOException("Połączenie zostało zamknięte");
        }
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
                    if (clientId == null) {
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
            shutdown("Połączenie z serwerem zostało zakończone");
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
        if (clientId != null) {
            return;
        }
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
            boolean epochChanged = newEpoch > epoch;
            boolean rosterChanged = !members.equals(newMembers);
            boolean leaderChanged = leaderId == null
                    ? newLeader != null : !leaderId.equals(newLeader);
            if (epochChanged) {
                epoch = newEpoch;
                cryptoContext = null;
            }
            if (epochChanged || rosterChanged || leaderChanged) {
                verifiedSasCode = null;
                verifiedSasEpoch = -1;
                sas = SasSnapshot.unavailable(
                        "Zmiana klucza lub składu pokoju — ponowne uzgadnianie SAS…");
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
            if (requestedEpoch != epoch || clientId == null || !clientId.equals(leaderId)
                    || closed.get()) {
                return;
            }
            EpochCryptoContext current = cryptoContext;
            if (current != null && current.epoch() == requestedEpoch) {
                return; // duplicate REKEY_REQUEST for an epoch that already has a key
            }
            newKey = CryptoSupport.newGroupKey();
            cryptoContext = new EpochCryptoContext(requestedEpoch, newKey, members.size());
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
        publishSas();
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
                    || !senderId.equals(leaderId) || senderId.equals(clientId) || closed.get()) {
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
                EpochCryptoContext current = cryptoContext;
                if (current != null && current.epoch() == acceptedEpoch) {
                    // Replayed envelopes must not reset the per-key encryption budget.
                    if (!MessageDigest.isEqual(current.key().getEncoded(), acceptedKey.getEncoded())) {
                        failure = "Sprzeczne klucze grupowe dla tej samej epoki";
                        acceptedKey = null;
                    }
                } else {
                    cryptoContext = new EpochCryptoContext(acceptedEpoch, acceptedKey, members.size());
                }
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
        publishSas();
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
                    || closed.get() || (cryptoContext != null && cryptoContext.epoch() == epoch)) {
                return;
            }
            requestedEpoch = epoch;
        }
        rotateAndDistributeKey(requestedEpoch);
    }

    private void sendAudio(byte[] opus) {
        try {
            EpochCryptoContext currentKey = cryptoContext;
            DatagramSocket socket = udpSocket;
            UUID sender = clientId;
            if (!running.get() || currentKey == null || socket == null
                    || sender == null || relayAddress == null || opus == null) {
                return;
            }
            long invocation = currentKey.claimEncryption();
            if (invocation >= currentKey.packetLimit()) {
                if (invocation == currentKey.packetLimit()) {
                    listener.onError("Osiągnięto limit użycia klucza AES-GCM. "
                            + "Dołącz ponownie do pokoju, aby zmienić klucz.");
                }
                return;
            }
            byte[] encrypted = currentKey.encrypt(sender, senderTag, opus);
            lastOutgoingPacket = capturePacketPreview(encrypted, currentKey.epoch());
            socket.send(new DatagramPacket(encrypted, encrypted.length, relayAddress));
        } catch (Exception exception) {
            // A single lost datagram must not interrupt the conversation.
        }
    }

    private void udpReceiveLoop() {
        byte[] buffer = new byte[Protocol.MAX_DATAGRAM_BYTES];
        DatagramSocket socket = udpSocket;
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                byte[] data = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
                if (handlePong(data)) {
                    continue;
                }

                EpochCryptoContext currentKey = cryptoContext;
                if (currentKey == null || AudioPacketCodec.peekEpoch(data) != currentKey.epoch()) {
                    continue;
                }
                AudioPacketCodec.DecodedPacket decoded = currentKey.decrypt(data);
                synchronized (stateLock) {
                    if (currentKey != cryptoContext || !running.get()) {
                        continue;
                    }
                    MemberInfo member = members.get(decoded.senderId());
                    if (member == null || member.senderTag() != decoded.senderTag()
                            || !currentKey.accept(decoded)) {
                        continue;
                    }
                    currentKey.recordDecrypted();
                    lastIncomingPacket = capturePacketPreview(data, currentKey.epoch());
                }
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
        retryRegistration(running::get, this::registerAndPing, () -> Thread.sleep(2_000));
    }

    private void registerAndPing() throws IOException {
        registerUdp();
        sendPing();
    }

    void sendPing() throws IOException {
        DatagramSocket socket = udpSocket;
        UUID id = clientId;
        byte[] token = relayToken;
        InetSocketAddress relay = relayAddress;
        if (socket == null || id == null || token == null || relay == null) {
            return;
        }
        long nonce = ThreadLocalRandom.current().nextLong();
        synchronized (pingLock) {
            pendingPingNonce = nonce;
            pendingPingSentNanos = System.nanoTime();
            hasPendingPing = true;
        }
        byte[] payload = ByteBuffer.allocate(Protocol.PING_DATAGRAM_BYTES)
                .putInt(Protocol.PING_MAGIC)
                .put(Protocol.VERSION)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .put(token)
                .putLong(nonce)
                .array();
        socket.send(new DatagramPacket(payload, payload.length, relay));
    }

    private boolean handlePong(byte[] data) {
        if (data.length != Protocol.PONG_DATAGRAM_BYTES) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (buffer.getInt() != Protocol.PONG_MAGIC || buffer.get() != Protocol.VERSION) {
            return false;
        }
        long nonce = buffer.getLong();
        long sentNanos;
        synchronized (pingLock) {
            if (!hasPendingPing || pendingPingNonce != nonce) {
                return true;
            }
            hasPendingPing = false;
            sentNanos = pendingPingSentNanos;
        }
        long rttMs = Math.max(1L, Math.round((System.nanoTime() - sentNanos) / 1_000_000.0));
        listener.onPingUpdated(rttMs);
        return true;
    }

    static void retryRegistration(BooleanSupplier running, IoAction register,
                                  InterruptibleAction wait) {
        while (running.getAsBoolean()) {
            try {
                register.run();
            } catch (IOException exception) {
                // Try again after the same delay as a successful registration.
            }
            try {
                wait.run();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface InterruptibleAction {
        void run() throws InterruptedException;
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
        return "voice-key/v" + Protocol.VERSION + "/" + room + "/" + keyEpoch
                + "/" + sender + "/" + recipient;
    }

    private void publishRoomState() {
        List<MemberInfo> snapshot;
        UUID self;
        UUID leader;
        int currentEpoch;
        boolean ready;
        SasSnapshot sasSnapshot;
        synchronized (stateLock) {
            snapshot = members.values().stream()
                    .sorted(Comparator.comparing(MemberInfo::nickname,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .toList();
            self = clientId;
            leader = leaderId;
            currentEpoch = epoch;
            ready = cryptoContext != null && cryptoContext.epoch() == currentEpoch;
            sasSnapshot = computeSasLocked();
            sas = sasSnapshot;
        }
        listener.onRoomState(snapshot, self, leader, currentEpoch);
        listener.onEncryptionChanged(ready, currentEpoch);
        listener.onSasChanged(sasSnapshot);
    }

    /** Recomputes and publishes SAS after the group key was installed or removed. */
    private void publishSas() {
        SasSnapshot snapshot;
        synchronized (stateLock) {
            snapshot = computeSasLocked();
            sas = snapshot;
        }
        listener.onSasChanged(snapshot);
    }

    /**
     * Must be called with {@link #stateLock} held. Verification is bound to the
     * exact code and epoch the user confirmed, so a new key, epoch or roster
     * automatically invalidates it.
     */
    private SasSnapshot computeSasLocked() {
        EpochCryptoContext context = cryptoContext;
        UUID self = clientId;
        UUID leader = leaderId;
        int currentEpoch = epoch;
        if (context == null || context.epoch() != currentEpoch) {
            return SasSnapshot.unavailable("Oczekiwanie na klucz grupowy…");
        }
        if (self == null || leader == null) {
            return SasSnapshot.unavailable("Brak pełnych danych pokoju do wyliczenia SAS");
        }
        MemberInfo selfMember = members.get(self);
        String ownPublicKey = CryptoSupport.encodePublicKey(identity.getPublic());
        if (selfMember == null || !ownPublicKey.equals(selfMember.publicKey())
                || selfMember.senderTag() != senderTag) {
            return SasSnapshot.unavailable(
                    "Lista uczestników nie zgadza się z tożsamością tego klienta");
        }
        try {
            String code = SasVerification.compute(
                    context.key(), room, currentEpoch, leader, members.values());
            boolean verified = code.equals(verifiedSasCode) && currentEpoch == verifiedSasEpoch;
            return new SasSnapshot(code, true, verified, currentEpoch, members.size(), null);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return SasSnapshot.unavailable(
                    "Nie można wyliczyć SAS: " + messageOf(exception));
        }
    }

    /** Returns the latest locally computed SAS; never transmitted to the server. */
    SasSnapshot sasSnapshot() {
        return sas;
    }

    /**
     * Marks the current SAS as verified locally. Returns {@code false} when no
     * code is available yet. This state is never sent through the network.
     */
    boolean setSasVerified(boolean verified) {
        SasSnapshot snapshot;
        boolean accepted = true;
        synchronized (stateLock) {
            snapshot = computeSasLocked();
            if (verified) {
                if (snapshot.available()) {
                    verifiedSasCode = snapshot.code();
                    verifiedSasEpoch = snapshot.epoch();
                    snapshot = new SasSnapshot(snapshot.code(), true, true,
                            snapshot.epoch(), snapshot.participants(), null);
                } else {
                    accepted = false;
                }
            } else {
                verifiedSasCode = null;
                verifiedSasEpoch = -1;
            }
            sas = snapshot;
        }
        listener.onSasChanged(snapshot);
        return accepted;
    }

    private void applyAudioConfig(OpusVoiceEngine engine) {
        engine.setMicrophoneGain(microphoneGain);
        engine.setNoiseGateThreshold(noiseGateThreshold);
        engine.setMasterVolume(masterVolume);
        engine.setPushToTalk(pttEnabled, pttPressed);
        engine.setMuted(manualMuted);
        engine.setDeafened(deafened);
        for (Map.Entry<UUID, Double> entry : participantVolumes.entrySet()) {
            engine.setParticipantVolume(entry.getKey(), entry.getValue());
        }
    }

    void setMuted(boolean muted) {
        this.manualMuted = muted;
        OpusVoiceEngine engine = audio;
        if (engine != null) {
            engine.setMuted(muted);
        }
    }

    void setPushToTalk(boolean enabled, boolean pressed) {
        this.pttEnabled = enabled;
        this.pttPressed = pressed;
        OpusVoiceEngine engine = audio;
        if (engine != null) {
            engine.setPushToTalk(enabled, pressed);
        }
    }

    void setMicrophoneGain(double gain) {
        this.microphoneGain = gain;
        OpusVoiceEngine engine = audio;
        if (engine != null) {
            engine.setMicrophoneGain(gain);
        }
    }

    void setNoiseGateThreshold(double threshold) {
        this.noiseGateThreshold = threshold;
        OpusVoiceEngine engine = audio;
        if (engine != null) {
            engine.setNoiseGateThreshold(threshold);
        }
    }

    void setMasterVolume(double volume) {
        this.masterVolume = volume;
        OpusVoiceEngine engine = audio;
        if (engine != null) {
            engine.setMasterVolume(volume);
        }
    }

    void setParticipantVolume(UUID participantId, double volume) {
        if (participantId != null) {
            participantVolumes.put(participantId, volume);
        }
        OpusVoiceEngine engine = audio;
        if (engine != null) {
            engine.setParticipantVolume(participantId, volume);
        }
    }

    void setDeafened(boolean deafened) {
        this.deafened = deafened;
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
        shutdown("Rozłączono");
    }

    private void shutdown(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        welcomed.countDown();
        synchronized (lifecycleLock) {
            running.set(false);
            Thread registration = registrationThread;
            if (registration != null) {
                registration.interrupt();
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
            try {
                if (audio != null) {
                    audio.close();
                }
            } catch (RuntimeException ignored) {
            } finally {
                synchronized (stateLock) {
                    cryptoContext = null;
                    pendingEnvelopes.clear();
                }
                notifyDisconnected(reason);
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

    private static PacketPreview capturePacketPreview(byte[] datagram, int packetEpoch) {
        if (datagram == null || datagram.length < Protocol.MIN_AUDIO_DATAGRAM_BYTES) {
            return null;
        }
        String nonceHex = CryptoSupport.toSpacedHex(
                datagram, Protocol.AUDIO_HEADER_BYTES - Protocol.GCM_NONCE_BYTES - Short.BYTES,
                Protocol.GCM_NONCE_BYTES);
        int cipherLen = datagram.length - Protocol.AUDIO_HEADER_BYTES;
        String cipherHex = CryptoSupport.toSpacedHex(
                datagram, Protocol.AUDIO_HEADER_BYTES, Math.min(14, cipherLen));
        if (cipherLen > 14) {
            cipherHex += " …";
        }
        return new PacketPreview(datagram.length, packetEpoch, nonceHex, cipherHex);
    }

    EncryptionSnapshot encryptionSnapshot() {
        EpochCryptoContext ctx = cryptoContext;
        return new EncryptionSnapshot(
                ctx != null ? ctx.epoch() : epoch,
                ctx != null,
                identityFingerprint,
                ctx != null ? ctx.keyFingerprint() : "—",
                ctx != null ? ctx.encryptedCount() : 0L,
                ctx != null ? ctx.decryptedCount() : 0L,
                controlPort,
                udpPort,
                lastOutgoingPacket,
                lastIncomingPacket
        );
    }

    record PacketPreview(int totalBytes, int epoch, String nonceHex, String ciphertextHex) {
    }

    /**
     * Local SAS state. {@code available} is false before the group key is known
     * or when the roster cannot be trusted; {@code problem} explains why.
     */
    record SasSnapshot(
            String code,
            boolean available,
            boolean verified,
            int epoch,
            int participants,
            String problem) {

        static SasSnapshot unavailable(String problem) {
            return new SasSnapshot("—", false, false, -1, 0, problem);
        }
    }

    record EncryptionSnapshot(
            int epoch,
            boolean active,
            String identityFingerprint,
            String groupKeyFingerprint,
            long encryptedPackets,
            long decryptedPackets,
            int controlPort,
            int udpPort,
            PacketPreview lastOutgoing,
            PacketPreview lastIncoming) {
    }

}
