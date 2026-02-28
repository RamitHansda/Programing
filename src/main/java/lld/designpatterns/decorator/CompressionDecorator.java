package lld.designpatterns.decorator;

import java.io.IOException;

/**
 * Decorator: decompresses on read. (Simplified: we assume delegate yields compressed bytes.)
 */
public final class CompressionDecorator extends StreamDecorator {

    public CompressionDecorator(DataStream delegate) {
        super(delegate);
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        // In production: read from delegate into temp buffer, decompress into buf
        // Placeholder: pass through
        return delegate.read(buf, off, len);
    }
}
