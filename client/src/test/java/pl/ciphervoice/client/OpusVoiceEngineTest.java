package pl.ciphervoice.client;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OpusVoiceEngineTest {
    @Test
    void speakerFailureRollsBackMicrophoneAndCloseIsIdempotentWithoutDrain() throws Exception {
        TargetDataLine microphone = mock(TargetDataLine.class);
        SourceDataLine speakers = mock(SourceDataLine.class);
        doThrow(new LineUnavailableException("speaker unavailable"))
                .when(speakers).open(any(AudioFormat.class), anyInt());
        OpusVoiceEngine engine = new OpusVoiceEngine(packet -> { }, level -> { },
                (id, level) -> { });

        try (MockedStatic<AudioSystem> audioSystem = mockStatic(AudioSystem.class)) {
            audioSystem.when(() -> AudioSystem.getLine(any(Line.Info.class)))
                    .thenReturn(microphone, speakers);

            assertThrows(LineUnavailableException.class, engine::start);
            engine.close();
            engine.close();
        }

        verify(microphone).open(any(AudioFormat.class), anyInt());
        verify(microphone).close();
        verify(speakers).close();
        verify(speakers).flush();
        verify(speakers, never()).drain();
        verify(microphone, never()).start();
    }

    @Test
    void failureToStopOneDeviceDoesNotPreventClosingEitherDevice() throws Exception {
        TargetDataLine microphone = mock(TargetDataLine.class);
        SourceDataLine speakers = mock(SourceDataLine.class);
        doThrow(new LineUnavailableException("speaker unavailable"))
                .when(speakers).open(any(AudioFormat.class), anyInt());
        doThrow(new IllegalStateException("device disconnected")).when(microphone).stop();
        OpusVoiceEngine engine = new OpusVoiceEngine(packet -> { }, level -> { },
                (id, level) -> { });

        try (MockedStatic<AudioSystem> audioSystem = mockStatic(AudioSystem.class)) {
            audioSystem.when(() -> AudioSystem.getLine(any(Line.Info.class)))
                    .thenReturn(microphone, speakers);
            assertThrows(LineUnavailableException.class, engine::start);
        }

        verify(microphone).close();
        verify(speakers).close();
        verify(speakers, never()).drain();
    }

    @Test
    void applyGainScalesAndClampsToPcmRange() {
        short[] pcm = {1000, -1000, 20_000, -25_000};
        OpusVoiceEngine.applyGain(pcm, pcm.length, 2.0);
        assertArrayEquals(new short[]{2000, -2000, Short.MAX_VALUE, Short.MIN_VALUE}, pcm);
    }

    @Test
    void noiseGateHoldsOpenDuringHangoverThenCloses() {
        OpusVoiceEngine.NoiseGate gate = new OpusVoiceEngine.NoiseGate(2);
        assertFalse(gate.accept(0.02, 0.10));
        assertTrue(gate.accept(0.15, 0.10));
        assertTrue(gate.accept(0.01, 0.10));
        assertTrue(gate.accept(0.01, 0.10));
        assertFalse(gate.accept(0.01, 0.10));
        assertTrue(gate.accept(0.0, 0.0));
    }

    @Test
    void mixIntoAndFinalizeMixedApplyPeerAndMasterVolumesWithoutIntermediateClipping() {
        int[] accum = new int[3];
        OpusVoiceEngine.mixInto(accum, new short[]{20_000, -20_000, 10_000}, 1.5);
        OpusVoiceEngine.mixInto(accum, new short[]{20_000, -20_000, 10_000}, 1.0);
        short[] output = new short[3];
        OpusVoiceEngine.finalizeMixed(accum, output, 0.5);
        assertArrayEquals(new short[]{25_000, -25_000, 12_500}, output);
    }

    @Test
    void pushToTalkRequiresHeldKeyUnlessDisabled() {
        OpusVoiceEngine engine = new OpusVoiceEngine(packet -> { }, level -> { },
                (id, level) -> { });
        assertFalse(engine.isTransmitMuted());

        engine.setPushToTalk(true, false);
        assertTrue(engine.isTransmitMuted());

        engine.setPushToTalk(true, true);
        assertFalse(engine.isTransmitMuted());

        engine.setMuted(true);
        assertTrue(engine.isTransmitMuted());
    }
}
