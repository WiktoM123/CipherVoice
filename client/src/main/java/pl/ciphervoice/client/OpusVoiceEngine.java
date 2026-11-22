package pl.ciphervoice.client;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Captures 20 ms PCM frames, encodes them with Opus and mixes the frames of all
 * remote participants into one playback stream.
 *
 * <p>Each participant has a small bounded queue. The playback thread consumes
 * one frame from every queue every 20 ms and mixes them, so several people can
 * speak at the same time without making the output backlog grow forever.</p>
 */
final class OpusVoiceEngine implements AutoCloseable {
    static final int SAMPLE_RATE = 48_000;
    static final int CHANNELS = 1;
    static final int FRAME_SAMPLES = 960; // 20 ms at 48 kHz
    private static final int FRAME_BYTES = FRAME_SAMPLES * Short.BYTES;
    private static final int MAX_QUEUED_FRAMES = 3;
    private static final int MAX_OPUS_PACKET_BYTES = 1276;
    private static final int LINE_BUFFER_BYTES = FRAME_BYTES * 4;
    static final int NOISE_GATE_HANGOVER_FRAMES = 15; // 300 ms at 20 ms/frame
    private static final System.Logger log =
            System.getLogger(OpusVoiceEngine.class.getName());

    private static final AudioFormat FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, CHANNELS,
            Short.BYTES, SAMPLE_RATE, false);

    private final Consumer<byte[]> packetConsumer;
    private final DoubleConsumer levelConsumer;
    private final BiConsumer<UUID, Double> participantLevelConsumer;
    private final Consumer<String> errorConsumer;
    private final Mixer.Info microphoneMixer;
    private final Mixer.Info speakerMixer;
    private final Map<UUID, ParticipantAudio> participants = new ConcurrentHashMap<>();
    private final Map<UUID, Double> participantVolumes = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean muted = new AtomicBoolean();
    private final AtomicBoolean pttEnabled = new AtomicBoolean();
    private final AtomicBoolean pttPressed = new AtomicBoolean();
    private final AtomicBoolean deafened = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile double microphoneGain = 1.0;
    private volatile double noiseGateThreshold = 0.0;
    private volatile double masterVolume = 1.0;
    private final Object lifecycleLock = new Object();
    private volatile TargetDataLine microphone;
    private volatile SourceDataLine speakers;

    OpusVoiceEngine(Consumer<byte[]> packetConsumer, DoubleConsumer levelConsumer,
                    BiConsumer<UUID, Double> participantLevelConsumer) {
        this(packetConsumer, levelConsumer, participantLevelConsumer, System.err::println,
                null, null);
    }

    OpusVoiceEngine(Consumer<byte[]> packetConsumer, DoubleConsumer levelConsumer,
                    BiConsumer<UUID, Double> participantLevelConsumer,
                    Consumer<String> errorConsumer) {
        this(packetConsumer, levelConsumer, participantLevelConsumer, errorConsumer, null, null);
    }

    OpusVoiceEngine(Consumer<byte[]> packetConsumer, DoubleConsumer levelConsumer,
                    BiConsumer<UUID, Double> participantLevelConsumer,
                    Consumer<String> errorConsumer, Mixer.Info microphoneMixer,
                    Mixer.Info speakerMixer) {
        this.packetConsumer = packetConsumer;
        this.levelConsumer = levelConsumer;
        this.participantLevelConsumer = participantLevelConsumer;
        this.errorConsumer = errorConsumer;
        this.microphoneMixer = microphoneMixer;
        this.speakerMixer = speakerMixer;
    }

    void start() throws Exception {
        synchronized (lifecycleLock) {
            if (closed.get() || running.get()) {
                throw new IllegalStateException("Silnik audio został już uruchomiony lub zamknięty");
            }
            try {
                DataLine.Info microphoneInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
                DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
                microphone = (TargetDataLine) (microphoneMixer == null
                        ? AudioSystem.getLine(microphoneInfo)
                        : AudioSystem.getMixer(microphoneMixer).getLine(microphoneInfo));
                speakers = (SourceDataLine) (speakerMixer == null
                        ? AudioSystem.getLine(speakerInfo)
                        : AudioSystem.getMixer(speakerMixer).getLine(speakerInfo));
                microphone.open(FORMAT, LINE_BUFFER_BYTES);
                speakers.open(FORMAT, LINE_BUFFER_BYTES);
                microphone.start();
                speakers.start();
                running.set(true);
                TargetDataLine captureLine = microphone;
                SourceDataLine playbackLine = speakers;
                Thread.ofPlatform().daemon(true).name("audio-capture")
                        .start(() -> captureLoop(captureLine));
                Thread.ofPlatform().daemon(true).name("audio-playback")
                        .start(() -> playbackLoop(playbackLine));
            } catch (Exception | Error exception) {
                close();
                throw exception;
            }
        }
    }

    static AudioFormat getFormat() {
        return FORMAT;
    }

    static List<Mixer.Info> getAvailableMicrophones() {
        return getAvailableMixers(TargetDataLine.class);
    }

    static List<Mixer.Info> getAvailableSpeakers() {
        return getAvailableMixers(SourceDataLine.class);
    }

    private static List<Mixer.Info> getAvailableMixers(Class<?> lineClass) {
        List<Mixer.Info> result = new ArrayList<>();
        DataLine.Info lineInfo = new DataLine.Info(lineClass, FORMAT);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(info).isLineSupported(lineInfo)) {
                    result.add(info);
                }
            } catch (Exception ignored) {
                // A disconnected device must not hide other available devices.
            }
        }
        return result;
    }

    /**
     * Decodes one packet and places the PCM frame in the sender's bounded queue.
     * Called from the single UDP receiver thread.
     */
    synchronized void play(UUID senderId, byte[] opus) {
        if (!running.get() || speakers == null || opus == null || opus.length == 0) {
            return;
        }
        try {
            ParticipantAudio participant = participants.computeIfAbsent(senderId,
                    ignored -> {
                        try {
                            return new ParticipantAudio(
                                    new OpusDecoder(SAMPLE_RATE, CHANNELS));
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
            short[] pcm = new short[FRAME_SAMPLES * CHANNELS];
            int samples = participant.decoder.decode(
                    opus, 0, opus.length, pcm, 0, FRAME_SAMPLES, false);
            if (samples <= 0) {
                return;
            }
            int sampleCount = samples * CHANNELS;
            participantLevelConsumer.accept(senderId, audioLevel(pcm, sampleCount));
            if (!deafened.get()) {
                participant.offer(Arrays.copyOf(pcm, sampleCount));
            }
        } catch (Exception exception) {
            // A corrupt or late packet must not stop the conversation.
        }
    }

    void removeParticipant(UUID participantId) {
        if (participantId != null) {
            participants.remove(participantId);
            participantVolumes.remove(participantId);
        }
    }

    private void captureLoop(TargetDataLine input) {
        try {
            OpusEncoder encoder = new OpusEncoder(SAMPLE_RATE, CHANNELS,
                    OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(24_000);
            encoder.setUseVBR(true);
            encoder.setUseInbandFEC(true);
            encoder.setPacketLossPercent(10);

            byte[] pcmBytes = new byte[FRAME_BYTES];
            byte[] opusBuffer = new byte[MAX_OPUS_PACKET_BYTES];
            NoiseGate gate = new NoiseGate(NOISE_GATE_HANGOVER_FRAMES);
            int levelFrame = 0;
            while (running.get()) {
                int read = readFrame(input, pcmBytes);
                if (read != pcmBytes.length) {
                    if (running.get()) {
                        throw new IllegalStateException("Mikrofon przerwał przechwytywanie dźwięku");
                    }
                    break;
                }
                short[] pcm = littleEndianToShorts(pcmBytes);
                applyGain(pcm, pcm.length, microphoneGain);
                double level = audioLevel(pcm, pcm.length);
                boolean transmitMuted = isTransmitMuted();
                boolean gateOpen = !transmitMuted && gate.accept(level, noiseGateThreshold);
                if (++levelFrame % 3 == 0) {
                    levelConsumer.accept(gateOpen ? level : 0.0);
                }
                if (!gateOpen) {
                    continue;
                }
                int encoded = encoder.encode(pcm, 0, FRAME_SAMPLES,
                        opusBuffer, 0, opusBuffer.length);
                if (encoded > 0) {
                    packetConsumer.accept(Arrays.copyOf(opusBuffer, encoded));
                }
            }
        } catch (Exception exception) {
            if (running.get()) {
                errorConsumer.accept("Błąd przechwytywania dźwięku: " + exception.getMessage());
            }
        } finally {
            close();
        }
    }

    private void playbackLoop(SourceDataLine output) {
        int[] accum = new int[FRAME_SAMPLES * CHANNELS];
        short[] mixed = new short[FRAME_SAMPLES * CHANNELS];
        byte[] mixedBytes = new byte[FRAME_BYTES];
        try {
            while (running.get()) {
                Arrays.fill(accum, 0);
                for (Map.Entry<UUID, ParticipantAudio> entry : participants.entrySet()) {
                    short[] frame = entry.getValue().poll();
                    if (frame != null) {
                        double peerVolume = participantVolumes.getOrDefault(entry.getKey(), 1.0);
                        mixInto(accum, frame, peerVolume);
                    }
                }
                finalizeMixed(accum, mixed, masterVolume);
                shortsToLittleEndian(mixed, mixedBytes);
                output.write(mixedBytes, 0, mixedBytes.length);
            }
        } catch (Exception exception) {
            if (running.get()) {
                log.log(System.Logger.Level.ERROR,
                        "Błąd odtwarzania dźwięku: " + exception.getMessage());
                errorConsumer.accept("Błąd odtwarzania dźwięku: " + exception.getMessage());
            }
        } finally {
            close();
        }
    }

    static void applyGain(short[] pcm, int count, double gain) {
        if (Math.abs(gain - 1.0) < 1e-6) {
            return;
        }
        double clampedGain = Math.max(0.0, Math.min(4.0, gain));
        for (int i = 0; i < count; i++) {
            long scaled = Math.round(pcm[i] * clampedGain);
            pcm[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
        }
    }

    static void mixInto(int[] target, short[] source, double gain) {
        int count = Math.min(target.length, source.length);
        double clampedGain = Math.max(0.0, Math.min(2.0, gain));
        for (int i = 0; i < count; i++) {
            target[i] += (int) Math.round(source[i] * clampedGain);
        }
    }

    static void finalizeMixed(int[] source, short[] target, double masterGain) {
        int count = Math.min(source.length, target.length);
        double clampedMaster = Math.max(0.0, Math.min(2.0, masterGain));
        for (int i = 0; i < count; i++) {
            long scaled = Math.round(source[i] * clampedMaster);
            target[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
        }
    }

    boolean isTransmitMuted() {
        return muted.get() || (pttEnabled.get() && !pttPressed.get());
    }

    void setMuted(boolean value) {
        muted.set(value);
        if (isTransmitMuted()) {
            levelConsumer.accept(0.0);
        }
    }

    void setPushToTalk(boolean enabled, boolean pressed) {
        pttEnabled.set(enabled);
        pttPressed.set(pressed);
        if (isTransmitMuted()) {
            levelConsumer.accept(0.0);
        }
    }

    void setMicrophoneGain(double gain) {
        microphoneGain = Math.max(0.25, Math.min(3.0, gain));
    }

    void setNoiseGateThreshold(double threshold) {
        noiseGateThreshold = Math.max(0.0, Math.min(0.5, threshold));
    }

    void setMasterVolume(double volume) {
        masterVolume = Math.max(0.0, Math.min(2.0, volume));
    }

    void setParticipantVolume(UUID participantId, double volume) {
        if (participantId != null) {
            participantVolumes.put(participantId, Math.max(0.0, Math.min(2.0, volume)));
        }
    }

    void setDeafened(boolean value) {
        deafened.set(value);
        if (value) {
            for (ParticipantAudio participant : participants.values()) {
                participant.clear();
            }
        }
    }

    static double audioLevel(short[] pcm, int count) {
        if (count <= 0) {
            return 0.0;
        }
        double sum = 0;
        for (int i = 0; i < count; i++) {
            double normalized = pcm[i] / 32768.0;
            sum += normalized * normalized;
        }
        double rms = Math.sqrt(sum / count);
        return Math.min(1.0, rms * 4.0);
    }

    private static int readFrame(TargetDataLine input, byte[] target) {
        int offset = 0;
        while (offset < target.length) {
            int count;
            try {
                count = input.read(target, offset, target.length - offset);
            } catch (IllegalStateException exception) {
                return offset;
            }
            if (count < 0) {
                break;
            }
            if (count == 0) {
                break;
            }
            offset += count;
        }
        return offset;
    }

    private static short[] littleEndianToShorts(byte[] bytes) {
        short[] result = new short[bytes.length / 2];
        for (int i = 0; i < result.length; i++) {
            int low = bytes[i * 2] & 0xff;
            int high = bytes[i * 2 + 1] << 8;
            result[i] = (short) (high | low);
        }
        return result;
    }

    private static void shortsToLittleEndian(short[] samples, byte[] target) {
        for (int i = 0; i < samples.length; i++) {
            target[i * 2] = (byte) samples[i];
            target[i * 2 + 1] = (byte) (samples[i] >>> 8);
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            running.set(false);
            TargetDataLine input = microphone;
            microphone = null;
            closeLine(input);
            SourceDataLine output = speakers;
            speakers = null;
            closeLine(output);
            participants.clear();
        }
    }

    private static void closeLine(AutoCloseable line) {
        if (line == null) {
            return;
        }
        try {
            if (line instanceof TargetDataLine target) {
                target.stop();
            } else if (line instanceof SourceDataLine source) {
                source.stop();
                source.flush();
            }
        } catch (Exception ignored) {
            // The line may already have been closed by the audio thread.
        } finally {
            try {
                line.close();
            } catch (Exception ignored) {
                // Closing the other line must still be attempted.
            }
        }
    }

    private static final class ParticipantAudio {
        private final OpusDecoder decoder;
        private final ArrayDeque<short[]> frames = new ArrayDeque<>(MAX_QUEUED_FRAMES + 1);

        private ParticipantAudio(OpusDecoder decoder) {
            this.decoder = decoder;
        }

        private synchronized void offer(short[] frame) {
            if (frames.size() >= MAX_QUEUED_FRAMES) {
                frames.removeFirst();
            }
            frames.addLast(frame);
        }

        private synchronized short[] poll() {
            return frames.pollFirst();
        }

        private synchronized void clear() {
            frames.clear();
        }
    }

    /**
     * Stateful voice-activity gate with hangover so quiet speech endings are not clipped.
     */
    static final class NoiseGate {
        private final int hangoverFrames;
        private int remainingHangover;

        NoiseGate(int hangoverFrames) {
            this.hangoverFrames = Math.max(0, hangoverFrames);
        }

        boolean accept(double level, double threshold) {
            if (threshold <= 0.0) {
                remainingHangover = 0;
                return true;
            }
            if (level >= threshold) {
                remainingHangover = hangoverFrames;
                return true;
            }
            if (remainingHangover > 0) {
                remainingHangover--;
                return true;
            }
            return false;
        }
    }
}
