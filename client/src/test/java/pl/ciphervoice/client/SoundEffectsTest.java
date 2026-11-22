package pl.ciphervoice.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundEffectsTest {
    @Test
    void synthesizesValid16BitPcmBufferForEveryCue() {
        for (SoundEffects.Cue cue : SoundEffects.Cue.values()) {
            byte[] pcm = SoundEffects.synthesize(cue, 1.0);
            assertTrue(pcm.length > 0);
            assertEquals(0, pcm.length % 2);
            boolean hasSignal = false;
            for (int i = 0; i < pcm.length; i += 2) {
                short sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                if (Math.abs(sample) > 100) {
                    hasSignal = true;
                    break;
                }
            }
            assertTrue(hasSignal, "Cue should contain audible samples: " + cue);
        }
    }
}
