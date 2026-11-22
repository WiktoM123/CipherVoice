package pl.ciphervoice.client;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSettingsTest {
    private final Preferences node = Preferences.userRoot()
            .node("ciphervoice-test-" + UUID.randomUUID());

    @AfterEach
    void tearDown() throws Exception {
        node.removeNode();
    }

    @Test
    void persistsAndClampsConnectionAndAudioPreferences() {
        AppSettings settings = new AppSettings(node);

        assertEquals("localhost", settings.serverHost());
        assertEquals("pokoj", settings.roomName());
        assertEquals(8090, settings.controlPort());
        assertEquals(8091, settings.udpPort());
        assertNull(settings.microphoneName());
        assertEquals(1.0, settings.microphoneGain());
        assertEquals(0.03, settings.noiseGateThreshold());
        assertEquals(1.0, settings.masterVolume());
        assertFalse(settings.pttEnabled());
        assertEquals(KeyCode.V, settings.pttKey());
        assertTrue(settings.systemSoundsEnabled());

        settings.setServerHost("  voice.example.org ");
        settings.setRoomName("zespol-1");
        settings.setNickname("Wiktor");
        settings.setControlPort(9000);
        settings.setUdpPort(9001);
        settings.setMicrophoneName("USB Mic");
        settings.setSpeakerName("Headphones");
        settings.setMicrophoneGain(10.0);
        settings.setNoiseGateThreshold(0.9);
        settings.setMasterVolume(-1.0);
        settings.setPttEnabled(true);
        settings.setPttKey(KeyCode.SPACE);
        settings.setSystemSoundsEnabled(false);

        AppSettings reloaded = new AppSettings(node);
        assertEquals("voice.example.org", reloaded.serverHost());
        assertEquals("zespol-1", reloaded.roomName());
        assertEquals("Wiktor", reloaded.nickname("fallback"));
        assertEquals(9000, reloaded.controlPort());
        assertEquals(9001, reloaded.udpPort());
        assertEquals("USB Mic", reloaded.microphoneName());
        assertEquals("Headphones", reloaded.speakerName());
        assertEquals(3.0, reloaded.microphoneGain());
        assertEquals(0.40, reloaded.noiseGateThreshold());
        assertEquals(0.0, reloaded.masterVolume());
        assertTrue(reloaded.pttEnabled());
        assertEquals(KeyCode.SPACE, reloaded.pttKey());
        assertFalse(reloaded.systemSoundsEnabled());

        reloaded.setMicrophoneName(null);
        assertNull(reloaded.microphoneName());
    }
}
