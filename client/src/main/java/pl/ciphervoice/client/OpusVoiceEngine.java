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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

final class OpusVoiceEngine implements AutoCloseable {
    static final int SAMPLE_RATE = 48_000;
    static final int CHANNELS = 1;
    static final int FRAME_SAMPLES = 960; // 20 ms przy 48 kHz
    private static final AudioFormat FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, CHANNELS, 2,
            SAMPLE_RATE, false);

    private final Consumer<byte[]> packetConsumer;
    private final DoubleConsumer levelConsumer;
    private final BiConsumer<UUID, Double> participantLevelConsumer;
    private final Consumer<String> errorConsumer;
    private final Mixer.Info microphoneMixer;
    private final Mixer.Info speakerMixer;
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean muted = new AtomicBoolean();
    private final AtomicBoolean deafened = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private volatile TargetDataLine microphone;
    private volatile SourceDataLine speakers;

    OpusVoiceEngine(Consumer<byte[]> packetConsumer, DoubleConsumer levelConsumer,
                    BiConsumer<UUID, Double> participantLevelConsumer) {
        this(packetConsumer, levelConsumer, participantLevelConsumer, System.err::println, null, null);
    }

    OpusVoiceEngine(Consumer<byte[]> packetConsumer, DoubleConsumer levelConsumer,
                    BiConsumer<UUID, Double> participantLevelConsumer,
                    Consumer<String> errorConsumer) {
        this(packetConsumer, levelConsumer, participantLevelConsumer, errorConsumer, null, null);
    }

    OpusVoiceEngine(Consumer<byte[]> packetConsumer, DoubleConsumer levelConsumer,
                    BiConsumer<UUID, Double> participantLevelConsumer,
                    Consumer<String> errorConsumer,
                    Mixer.Info microphoneMixer, Mixer.Info speakerMixer) {
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
                if (microphoneMixer != null) {
                    microphone = (TargetDataLine) AudioSystem.getMixer(microphoneMixer).getLine(microphoneInfo);
                } else {
                    microphone = (TargetDataLine) AudioSystem.getLine(microphoneInfo);
                }
                if (speakerMixer != null) {
                    speakers = (SourceDataLine) AudioSystem.getMixer(speakerMixer).getLine(speakerInfo);
                } else {
                    speakers = (SourceDataLine) AudioSystem.getLine(speakerInfo);
                }
                microphone.open(FORMAT, FRAME_SAMPLES * 2 * 8);
                speakers.open(FORMAT, FRAME_SAMPLES * 2 * 8);
                microphone.start();
                speakers.start();
                running.set(true);
                Thread.ofVirtual().name("audio-capture").start(this::captureLoop);
            } catch (Exception | Error exception) {
                close();
                throw exception;
            }
        }
    }

    public static AudioFormat getFormat() {
        return FORMAT;
    }

    public static List<Mixer.Info> getAvailableMicrophones() {
        List<Mixer.Info> list = new ArrayList<>();
        DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(info);
                if (mixer.isLineSupported(targetInfo)) {
                    list.add(info);
                }
            } catch (Exception ignored) {}
        }
        return list;
    }

    public static List<Mixer.Info> getAvailableSpeakers() {
        List<Mixer.Info> list = new ArrayList<>();
        DataLine.Info sourceInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(info);
                if (mixer.isLineSupported(sourceInfo)) {
                    list.add(info);
                }
            } catch (Exception ignored) {}
        }
        return list;
    }

    synchronized void play(UUID senderId, byte[] opus) {
        SourceDataLine output = speakers;
        if (!running.get() || output == null) {
            return;
        }
        try {
            OpusDecoder decoder = decoders.computeIfAbsent(senderId, ignored -> {
                try {
                    return new OpusDecoder(SAMPLE_RATE, CHANNELS);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            short[] pcm = new short[FRAME_SAMPLES * CHANNELS];
            int samples = decoder.decode(opus, 0, opus.length, pcm, 0, FRAME_SAMPLES, false);
            participantLevelConsumer.accept(senderId,
                    audioLevel(pcm, samples * CHANNELS));
            if (deafened.get()) {
                return;
            }
            byte[] bytes = shortsToLittleEndian(pcm, samples * CHANNELS);
            output.write(bytes, 0, bytes.length);
        } catch (Exception ignored) {
            // Uszkodzony lub spóźniony pakiet nie zatrzymuje rozmowy.
        }
    }

    private void captureLoop() {
        try {
            OpusEncoder encoder = new OpusEncoder(SAMPLE_RATE, CHANNELS,
                    OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(24_000);
            encoder.setUseVBR(true);
            encoder.setUseInbandFEC(true);
            encoder.setPacketLossPercent(10);
            byte[] pcmBytes = new byte[FRAME_SAMPLES * 2];
            byte[] opusBuffer = new byte[1276];
            int levelFrame = 0;
            while (running.get()) {
                int read = readFrame(pcmBytes);
                if (read != pcmBytes.length) {
                    if (running.get()) {
                        throw new IllegalStateException("Mikrofon przerwał przechwytywanie dźwięku");
                    }
                    break;
                }
                short[] pcm = littleEndianToShorts(pcmBytes);
                if (++levelFrame % 3 == 0) {
                    levelConsumer.accept(muted.get() || deafened.get()
                            ? 0.0 : audioLevel(pcm, pcm.length));
                }
                if (muted.get() || deafened.get()) {
                    continue;
                }
                int encoded = encoder.encode(pcm, 0, FRAME_SAMPLES,
                        opusBuffer, 0, opusBuffer.length);
                packetConsumer.accept(Arrays.copyOf(opusBuffer, encoded));
            }
        } catch (Exception exception) {
            if (running.get()) {
                errorConsumer.accept("Błąd przechwytywania dźwięku: " + exception.getMessage());
            }
        } finally {
            close();
        }
    }

    void setMuted(boolean value) {
        muted.set(value);
        if (value) {
            levelConsumer.accept(0.0);
        }
    }

    void setDeafened(boolean value) {
        deafened.set(value);
        if (value) {
            levelConsumer.accept(0.0);
        }
    }

    private static double audioLevel(short[] pcm, int count) {
        if (count <= 0) {
            return 0.0;
        }
        double sum = 0;
        for (int i = 0; i < count; i++) {
            short sample = pcm[i];
            double normalized = sample / 32768.0;
            sum += normalized * normalized;
        }
        double rms = Math.sqrt(sum / count);
        return Math.min(1.0, rms * 4.0);
    }

    private int readFrame(byte[] target) {
        int offset = 0;
        TargetDataLine input = microphone;
        while (running.get() && offset < target.length) {
            int count = input.read(target, offset, target.length - offset);
            if (count <= 0) {
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

    private static byte[] shortsToLittleEndian(short[] samples, int count) {
        byte[] result = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            result[i * 2] = (byte) samples[i];
            result[i * 2 + 1] = (byte) (samples[i] >>> 8);
        }
        return result;
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            running.set(false);
            TargetDataLine input = microphone;
            SourceDataLine output = speakers;
            microphone = null;
            speakers = null;
            if (input != null) {
                try {
                    input.stop();
                } catch (RuntimeException ignored) {
                } finally {
                    try {
                        input.close();
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            if (output != null) {
                try {
                    output.stop();
                    output.flush();
                } catch (RuntimeException ignored) {
                } finally {
                    try {
                        output.close();
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            decoders.clear();
        }
    }
}

