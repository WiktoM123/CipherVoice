package pl.ciphervoice.client;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
