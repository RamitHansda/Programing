package lld.designpatterns.decorator;

import java.io.IOException;
import java.util.Objects;

/**
 * Concrete stream (e.g. reading from memory or file).
 */
public final class BaseStream implements DataStream {

    private final byte[] source;
    private int position;

    public BaseStream(byte[] source) {
        this.source = source != null ? source : new byte[0];
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (position >= source.length) {
            return -1;
        }
        int toRead = Math.min(len, source.length - position);
        System.arraycopy(source, position, buf, off, toRead);
        position += toRead;
        return toRead;
    }
}
