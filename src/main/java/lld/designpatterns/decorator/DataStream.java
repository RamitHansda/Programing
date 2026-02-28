package lld.designpatterns.decorator;

import java.io.IOException;

/**
 * Decorator: base abstraction for stream. Decorators wrap and add behavior (compress, encrypt, buffer, log).
 */
public interface DataStream {

    /**
     * Reads up to len bytes into buf. Returns number of bytes read, or -1 at end of stream.
     */
    int read(byte[] buf, int off, int len) throws IOException;

    default int read(byte[] buf) throws IOException {
        return read(buf, 0, buf.length);
    }
}
