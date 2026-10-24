package pl.edu.voice.common;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;

/**
 * Newline-delimited JSON transport helpers.
 *
 * <p>{@link BufferedReader#readLine()} has no size limit, so a malicious peer
 * could allocate an arbitrarily large string. These helpers enforce a small
 * upper bound while keeping the wire format unchanged.</p>
 */
public final class JsonLines {
    private JsonLines() {
    }

    /**
     * Reads one line without its CR/LF terminator, or {@code null} at EOF.
     *
     * @throws IOException when the line exceeds {@code maxChars}
     */
    public static String read(BufferedReader reader, int maxChars) throws IOException {
        StringBuilder line = new StringBuilder(128);
        int character;
        while ((character = reader.read()) != -1) {
            if (character == '\n') {
                return line.toString();
            }
            if (character == '\r') {
                continue;
            }
            if (line.length() >= maxChars) {
                throw new IOException("Linia komunikatu jest zbyt długa");
            }
            line.append((char) character);
        }
        return line.isEmpty() ? null : line.toString();
    }

    public static void write(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
        writer.flush();
    }
}
