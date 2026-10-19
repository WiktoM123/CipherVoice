package pl.edu.voice.client;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean muted = new AtomicBoolean();
    private final AtomicBoolean deafened = new AtomicBoolean();
    private TargetDataLine microphone;
    private SourceDataLine speakers;

    OpusVoiceEngine(Consumer<byte[]> packetConsumer, DoubleConsumer levelConsumer) {
        this.packetConsumer = packetConsumer;
        this.levelConsumer = levelConsumer;
    }

    void start() throws Exception {
        DataLine.Info microphoneInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
        DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
        microphone = (TargetDataLine) AudioSystem.getLine(microphoneInfo);
        speakers = (SourceDataLine) AudioSystem.getLine(speakerInfo);
        microphone.open(FORMAT, FRAME_SAMPLES * 2 * 8);
        speakers.open(FORMAT, FRAME_SAMPLES * 2 * 8);
        microphone.start();
        speakers.start();
        running.set(true);
        Thread.ofVirtual().name("audio-capture").start(this::captureLoop);
    }

    synchronized void play(UUID senderId, byte[] opus) {
        if (!running.get() || speakers == null || deafened.get()) {
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
            byte[] bytes = shortsToLittleEndian(pcm, samples * CHANNELS);
            speakers.write(bytes, 0, bytes.length);
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
                    continue;
                }
                short[] pcm = littleEndianToShorts(pcmBytes);
                if (++levelFrame % 3 == 0) {
                    levelConsumer.accept(muted.get() || deafened.get() ? 0.0 : audioLevel(pcm));
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
                System.err.println("Błąd przechwytywania dźwięku: " + exception.getMessage());
            }
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

    private static double audioLevel(short[] pcm) {
        double sum = 0;
        for (short sample : pcm) {
            double normalized = sample / 32768.0;
            sum += normalized * normalized;
        }
        double rms = Math.sqrt(sum / pcm.length);
        return Math.min(1.0, rms * 4.0);
    }

    private int readFrame(byte[] target) {
        int offset = 0;
        while (running.get() && offset < target.length) {
            int count = microphone.read(target, offset, target.length - offset);
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
        running.set(false);
        if (microphone != null) {
            microphone.stop();
            microphone.close();
        }
        if (speakers != null) {
            speakers.drain();
            speakers.stop();
            speakers.close();
        }
    }
}

