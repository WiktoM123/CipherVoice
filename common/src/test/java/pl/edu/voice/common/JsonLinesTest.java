package pl.edu.voice.common;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonLinesTest {
    @Test
    void readsLfAndCrLfAndReportsEof() throws Exception {
        BufferedReader reader = new BufferedReader(new StringReader("pierwsza\r\ndruga\n"));
        assertEquals("pierwsza", JsonLines.read(reader, 100));
        assertEquals("druga", JsonLines.read(reader, 100));
        assertNull(JsonLines.read(reader, 100));
    }

    @Test
    void rejectsLinesAboveTheLimit() {
        BufferedReader reader = new BufferedReader(new StringReader("a".repeat(11) + "\n"));
        assertThrows(IOException.class, () -> JsonLines.read(reader, 10));
    }
}
