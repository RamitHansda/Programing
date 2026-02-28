package lld.designpatterns.decorator;

import java.io.IOException;
import java.util.Objects;

/**
 * Base decorator: wraps another stream and delegates. Subclasses override read() to add behavior.
 */
public abstract class StreamDecorator implements DataStream {

    protected final DataStream delegate;

    protected StreamDecorator(DataStream delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        return delegate.read(buf, off, len);
    }
}
