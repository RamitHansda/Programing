package lld.designpatterns.decorator;

import java.io.IOException;

/**
 * Decorator: buffers reads; batches small reads from delegate.
 */
public final class BufferingDecorator extends StreamDecorator {

    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private final byte[] buffer;
    private int bufferPos;
    private int bufferLen = -1;

    public BufferingDecorator(DataStream delegate) {
        this(delegate, DEFAULT_BUFFER_SIZE);
    }

    public BufferingDecorator(DataStream delegate, int bufferSize) {
        super(delegate);
        this.buffer = new byte[Math.max(256, bufferSize)];
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (len <= 0) return 0;
        if (bufferLen < 0) {
            bufferLen = delegate.read(buffer, 0, buffer.length);
            bufferPos = 0;
        }
        if (bufferLen <= 0) return -1;
        int toCopy = Math.min(len, bufferLen - bufferPos);
        System.arraycopy(buffer, bufferPos, buf, off, toCopy);
        bufferPos += toCopy;
        if (bufferPos >= bufferLen) {
            bufferLen = -1;
        }
        return toCopy;
    }
}
