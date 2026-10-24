package pl.edu.voice.client;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
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
    private static final System.Logger log =
            System.getLogger(OpusVoiceEngine.class.getName());

    private static final AudioFormat FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, CHANNELS,
            Short.BYTES, SAMPLE_RATE, false);

    private final Consumer<byte[]> packetConsumer;
    private final DoubleConsumer levelConsumer;
    private final BiConsumer<UUID, Double> participantLevelConsumer;
    private final Map<UUID, ParticipantAudio> participants = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean muted = new AtomicBoolean();
    private final AtomicBoolean deafened = new AtomicBoolean();
    private volatile TargetDataLine microphone;
    private volatile SourceDataLine speakers;

    OpusVoiceEngine(Consumer<byte[]> packetConsumer, DoubleConsumer levelConsumer,
                    BiConsumer<UUID, Double> participantLevelConsumer) {
        this.packetConsumer = packetConsumer;
        this.levelConsumer = levelConsumer;
        this.participantLevelConsumer = participantLevelConsumer;
    }

    void start() throws Exception {
        TargetDataLine input = null;
        SourceDataLine output = null;
        try {
            input = (TargetDataLine) AudioSystem.getLine(
                    new DataLine.Info(TargetDataLine.class, FORMAT));
            output = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, FORMAT));
            input.open(FORMAT, LINE_BUFFER_BYTES);
            output.open(FORMAT, LINE_BUFFER_BYTES);
            input.start();
            output.start();
        } catch (Exception exception) {
            closeLine(input);
            closeLine(output);
            throw exception;
        }

        microphone = input;
        speakers = output;
        running.set(true);
        TargetDataLine captureLine = input;
        SourceDataLine playbackLine = output;
        Thread.ofPlatform().daemon(true).name("audio-capture")
                .start(() -> captureLoop(captureLine));
        Thread.ofPlatform().daemon(true).name("audio-playback")
                .start(() -> playbackLoop(playbackLine));
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
            int levelFrame = 0;
            while (running.get()) {
                int read = readFrame(input, pcmBytes);
                if (read != pcmBytes.length) {
                    if (running.get()) {
                        Thread.sleep(5);
                    }
                    continue;
                }
                short[] pcm = littleEndianToShorts(pcmBytes);
                if (++levelFrame % 3 == 0) {
                    levelConsumer.accept(muted.get() ? 0.0 : audioLevel(pcm, pcm.length));
                }
                if (muted.get()) {
                    continue;
                }
                int encoded = encoder.encode(pcm, 0, FRAME_SAMPLES,
                        opusBuffer, 0, opusBuffer.length);
                if (encoded > 0) {
                    packetConsumer.accept(Arrays.copyOf(opusBuffer, encoded));
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            if (running.get()) {
                log.log(System.Logger.Level.ERROR,
                        "Błąd przechwytywania dźwięku: " + exception.getMessage());
            }
        }
    }

    private void playbackLoop(SourceDataLine output) {
        short[] mixed = new short[FRAME_SAMPLES * CHANNELS];
        byte[] mixedBytes = new byte[FRAME_BYTES];
        try {
            while (running.get()) {
                Arrays.fill(mixed, (short) 0);
                for (ParticipantAudio participant : participants.values()) {
                    short[] frame = participant.poll();
                    if (frame != null) {
                        mixInto(mixed, frame);
                    }
                }
                shortsToLittleEndian(mixed, mixedBytes);
                output.write(mixedBytes, 0, mixedBytes.length);
            }
        } catch (Exception exception) {
            if (running.get()) {
                log.log(System.Logger.Level.ERROR,
                        "Błąd odtwarzania dźwięku: " + exception.getMessage());
            }
        }
    }

    private static void mixInto(short[] target, short[] source) {
        int count = Math.min(target.length, source.length);
        for (int i = 0; i < count; i++) {
            int sum = target[i] + source[i];
            target[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum));
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
            for (ParticipantAudio participant : participants.values()) {
                participant.clear();
            }
        }
    }

    private static double audioLevel(short[] pcm, int count) {
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
        running.set(false);
        TargetDataLine input = microphone;
        microphone = null;
        closeLine(input);
        SourceDataLine output = speakers;
        speakers = null;
        closeLine(output);
        participants.clear();
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
            }
            line.close();
        } catch (Exception ignored) {
            // The line may already have been closed by the audio thread.
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
}
