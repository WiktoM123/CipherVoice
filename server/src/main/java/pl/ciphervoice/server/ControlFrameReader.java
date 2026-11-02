package pl.ciphervoice.server;

import pl.ciphervoice.common.Protocol;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads newline-delimited UTF-8 without buffering an unbounded control message. */
final class ControlFrameReader implements Closeable {
    private final InputStream input;
    private boolean skipLineFeed;

    ControlFrameReader(InputStream input) {
        this.input = new BufferedInputStream(input);
    }

    String readFrame() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value = input.read();
        if (skipLineFeed) {
            skipLineFeed = false;
            if (value == '\n') {
                value = input.read();
            }
        }
        while (value != -1) {
            if (value == '\n' || value == '\r') {
                skipLineFeed = value == '\r';
                return bytes.toString(StandardCharsets.UTF_8);
            }
            if (bytes.size() == Protocol.MAX_CONTROL_FRAME_BYTES) {
                throw new IOException("Wiadomość sterująca przekracza dozwolony rozmiar");
            }
            bytes.write(value);
            value = input.read();
        }
        return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
