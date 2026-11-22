package pl.ciphervoice.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Synthesizes subtle UI feedback chimes for room membership and mute state changes.
 */
final class SoundEffects {
    enum Cue {
        JOIN,
        LEAVE,
        MUTE,
        UNMUTE
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = Thread.ofPlatform().daemon(true).name("ui-sound-effects").unstarted(runnable);
        return thread;
    });

    void playAsync(Cue cue, Mixer.Info speakerMixer, double volume) {
        if (cue == null) {
            return;
        }
        double clampedVolume = Math.max(0.0, Math.min(1.5, volume));
        if (clampedVolume <= 0.01) {
            return;
        }
        executor.execute(() -> playBlocking(cue, speakerMixer, clampedVolume));
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private static void playBlocking(Cue cue, Mixer.Info speakerMixer, double volume) {
        AudioFormat format = OpusVoiceEngine.getFormat();
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        try (SourceDataLine line = speakerMixer != null
                ? (SourceDataLine) AudioSystem.getMixer(speakerMixer).getLine(info)
                : (SourceDataLine) AudioSystem.getLine(info)) {
            line.open(format, 960 * 4);
            line.start();
            byte[] pcm = synthesize(cue, volume);
            line.write(pcm, 0, pcm.length);
            line.drain();
        } catch (Exception ignored) {
            // Sound cues are optional and must never disturb the call.
        }
    }

    static byte[] synthesize(Cue cue, double volume) {
        return switch (cue) {
            case JOIN -> twoTone(523.25, 659.25, 0.075, 0.095, 4200 * volume);
            case LEAVE -> twoTone(587.33, 440.00, 0.075, 0.095, 4200 * volume);
            case MUTE -> twoTone(440.00, 329.63, 0.045, 0.055, 3600 * volume);
            case UNMUTE -> twoTone(392.00, 523.25, 0.045, 0.055, 3600 * volume);
        };
    }

    private static byte[] twoTone(double freq1, double freq2, double sec1, double sec2, double amp) {
        int sr = OpusVoiceEngine.SAMPLE_RATE;
        int n1 = (int) (sr * sec1);
        int n2 = (int) (sr * sec2);
        byte[] buffer = new byte[(n1 + n2) * 2];
        writeTone(buffer, 0, n1, freq1, amp, sr);
        writeTone(buffer, n1, n2, freq2, amp, sr);
        return buffer;
    }

    private static void writeTone(byte[] target, int offsetSamples, int count,
                                  double freq, double amp, int sampleRate) {
        double cappedAmp = Math.min(24_000.0, amp);
        for (int i = 0; i < count; i++) {
            double envelope = Math.sin(Math.PI * i / Math.max(1, count));
            double angle = 2.0 * Math.PI * i * freq / sampleRate;
            short sample = (short) Math.round(Math.sin(angle) * envelope * cappedAmp);
            int idx = (offsetSamples + i) * 2;
            target[idx] = (byte) (sample & 0xFF);
            target[idx + 1] = (byte) ((sample >> 8) & 0xFF);
        }
    }
}
