package pl.ciphervoice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.function.BooleanSupplier;

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
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch welcomed = new CountDownLatch(1);
    private final Object stateLock = new Object();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean disconnectNotified = new AtomicBoolean();

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
    private final Mixer.Info microphoneMixer;
    private final Mixer.Info speakerMixer;

    VoiceClient(String host, int controlPort, int udpPort, String room, String nickname,
                VoiceClientListener listener)
            throws Exception {
        this(host, controlPort, udpPort, room, nickname, listener, null, null);
    }

    VoiceClient(String host, int controlPort, int udpPort, String room, String nickname,
                VoiceClientListener listener, Mixer.Info microphoneMixer, Mixer.Info speakerMixer)
            throws Exception {
        this.host = host;
        this.controlPort = controlPort;
        this.udpPort = udpPort;
        this.room = room;
        this.nickname = nickname;
        this.listener = listener == null ? new VoiceClientListener() { } : listener;
        this.identity = CryptoSupport.newIdentityKeyPair();
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
        controlWriter = new BufferedWriter(new OutputStreamWriter(
                controlSocket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                controlSocket.getInputStream(), StandardCharsets.UTF_8));
        synchronized (lifecycleLock) {
            requireOpen();
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
            throw new IOException("Nie udało się dołączyć do pokoju");
        }

        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(host), udpPort);
        synchronized (lifecycleLock) {
            requireOpen();
            relayAddress = address;
            udpSocket = new DatagramSocket();
            udpSocket.connect(relayAddress);
            registerUdp();
            Thread.ofVirtual().name("udp-receiver").start(this::udpReceiveLoop);
            registrationThread = Thread.ofVirtual().name("udp-registration")
                    .start(this::registrationLoop);

            audio = new OpusVoiceEngine(this::sendAudio, listener::onLocalAudioLevel,
                    listener::onParticipantAudioLevel, listener::onError,
                    microphoneMixer, speakerMixer);
            try {
                audio.start();
                listener.onStatus("Połączono — mikrofon i głośniki są aktywne");
            } catch (Exception exception) {
                audio.close();
                audio = null;
                listener.onError("Połączono, ale nie można uruchomić audio: "
                        + exception.getMessage());
            }
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
            while (running.get() && (line = reader.readLine()) != null) {
                handleControl(mapper.readValue(line, WireMessage.class));
            }
        } catch (Exception exception) {
            if (running.get()) {
                listener.onError("Utracono połączenie sterujące: " + exception.getMessage());
            }
        } finally {
            shutdown("Połączenie z serwerem zostało zakończone");
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
            if (clientId != null) {
                return;
            }
            clientId = UUID.fromString(message.clientId);
            senderTag = message.senderTag;
            relayToken = Base64.getDecoder().decode(message.relayToken);
            updateRoomState(message);
            listener.onStatus("Dołączono do pokoju " + room);
            welcomed.countDown();
        } else if (message.type == MessageType.ROOM_STATE) {
            if (!updateRoomState(message)) {
                return;
            }
        } else if (message.type == MessageType.REKEY_REQUEST) {
            if (!updateRoomState(message)) {
                return;
            }
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

    private boolean updateRoomState(WireMessage message) {
        synchronized (stateLock) {
            int newEpoch = message.epoch == null ? epoch : message.epoch;
            if (newEpoch < epoch) {
                return false;
            }
            if (newEpoch != epoch) {
                cryptoContext = null;
            }
            epoch = newEpoch;
            leaderId = message.leaderId == null ? null : UUID.fromString(message.leaderId);
            members.clear();
            if (message.members != null) {
                for (MemberInfo member : message.members) {
                    members.put(UUID.fromString(member.clientId()), member);
                }
            }
            return true;
        }
    }

    private void rotateAndDistributeKey(int requestedEpoch) throws Exception {
        SecretKeySpec newKey;
        Map<UUID, MemberInfo> roster;
        synchronized (stateLock) {
            if (requestedEpoch != epoch || !clientId.equals(leaderId)
                    || cryptoContext != null || closed.get()) {
                return;
            }
            newKey = CryptoSupport.newGroupKey();
            cryptoContext = new EpochCryptoContext(requestedEpoch, newKey);
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
                    || !senderId.equals(leaderId) || cryptoContext != null || closed.get()) {
                return;
            }
            MemberInfo sender = members.get(senderId);
            if (sender == null) {
                return;
            }
            PublicKey senderKey = CryptoSupport.decodePublicKey(sender.publicKey());
            String context = keyContext(epoch, senderId, clientId);
            SecretKeySpec key = CryptoSupport.unwrapGroupKey(
                    new CryptoSupport.WrappedKey(message.wrapNonce, message.wrappedKey),
                    identity.getPrivate(), senderKey, context);
            cryptoContext = new EpochCryptoContext(epoch, key);
        }
        listener.onEncryptionChanged(true, epoch);
    }

    private void sendAudio(byte[] opus) {
        try {
            EpochCryptoContext context = cryptoContext;
            DatagramSocket socket = udpSocket;
            if (!running.get() || context == null || socket == null || clientId == null) {
                return;
            }
            byte[] encrypted = context.encrypt(clientId, senderTag, opus);
            socket.send(new DatagramPacket(encrypted, encrypted.length, relayAddress));
        } catch (Exception exception) {
            // Pojedynczy utracony datagram nie powinien przerywać rozmowy.
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
                EpochCryptoContext context = cryptoContext;
                if (context == null || AudioPacketCodec.peekEpoch(data) != context.epoch()) {
                    continue;
                }
                AudioPacketCodec.DecodedPacket decoded = context.decrypt(data);
                synchronized (stateLock) {
                    if (context != cryptoContext || !running.get()) {
                        continue;
                    }
                    MemberInfo member = members.get(decoded.senderId());
                    if (member == null || member.senderTag() != decoded.senderTag()
                            || !context.accept(decoded)) {
                        continue;
                    }
                }
                OpusVoiceEngine engine = audio;
                if (engine != null) {
                    engine.play(decoded.senderId(), decoded.opus());
                }
            } catch (Exception exception) {
                // Nieprawidłowy lub spóźniony datagram jest pomijany.
            }
        }
    }

    private void registrationLoop() {
        retryRegistration(running::get, this::registerUdp, () -> Thread.sleep(3_000));
    }

    static void retryRegistration(BooleanSupplier active, RegistrationAttempt attempt,
                                  RetryDelay delay) {
        while (active.getAsBoolean()) {
            try {
                attempt.run();
            } catch (IOException ignored) {
                // Opóźnienie obowiązuje także po nieudanej próbie.
            }
            if (!active.getAsBoolean()) {
                return;
            }
            try {
                delay.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @FunctionalInterface
    interface RegistrationAttempt {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface RetryDelay {
        void await() throws InterruptedException;
    }

    private void registerUdp() throws IOException {
        DatagramSocket socket = udpSocket;
        if (socket == null || clientId == null || relayToken == null) {
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
        socket.send(new DatagramPacket(data, data.length, relayAddress));
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
        listener.onEncryptionChanged(cryptoContext != null, epoch);
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
                }
                notifyDisconnected(reason);
            }
        }
    }
}

