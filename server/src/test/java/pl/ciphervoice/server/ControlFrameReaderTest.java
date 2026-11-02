package pl.ciphervoice.server;

import org.junit.jupiter.api.Test;
import pl.ciphervoice.common.Protocol;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ControlFrameReaderTest {
    @Test
    void readsUtf8AndAllSupportedLineEndings() throws Exception {
        try (ControlFrameReader reader = reader("zażółć\r\ndrugi\ntrzeci\rczwarty")) {
            assertEquals("zażółć", reader.readFrame());
            assertEquals("drugi", reader.readFrame());
            assertEquals("trzeci", reader.readFrame());
            assertEquals("czwarty", reader.readFrame());
            assertNull(reader.readFrame());
        }
    }

    @Test
    void acceptsTheExactByteLimit() throws Exception {
        String frame = "a".repeat(Protocol.MAX_CONTROL_FRAME_BYTES);
        try (ControlFrameReader reader = reader(frame + "\n")) {
            assertEquals(frame, reader.readFrame());
            assertNull(reader.readFrame());
        }
    }

    @Test
    void rejectsOverlongFramesEvenWithoutATerminatingNewline() throws Exception {
        try (ControlFrameReader reader = reader("a".repeat(Protocol.MAX_CONTROL_FRAME_BYTES + 1))) {
            assertThrows(IOException.class, reader::readFrame);
        }
    }

    @Test
    void countsUtf8BytesRatherThanJavaCharacters() throws Exception {
        try (ControlFrameReader reader = reader("ą".repeat(Protocol.MAX_CONTROL_FRAME_BYTES / 2 + 1))) {
            assertThrows(IOException.class, reader::readFrame);
        }
    }

    private static ControlFrameReader reader(String value) {
        return new ControlFrameReader(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }
}
