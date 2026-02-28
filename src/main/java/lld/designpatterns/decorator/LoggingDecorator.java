package lld.designpatterns.decorator;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Decorator: logs every read (e.g. for debugging or audit).
 */
public final class LoggingDecorator extends StreamDecorator {

    private static final Logger LOG = Logger.getLogger(LoggingDecorator.class.getName());

    public LoggingDecorator(DataStream delegate) {
        super(delegate);
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n = delegate.read(buf, off, len);
        LOG.fine(() -> "read(n=" + n + ", off=" + off + ", len=" + len + ")");
        return n;
    }
}
