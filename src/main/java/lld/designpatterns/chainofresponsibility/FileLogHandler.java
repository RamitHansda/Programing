package lld.designpatterns.chainofresponsibility;

public final class FileLogHandler extends LogHandler {

    private final String filePath;

    public FileLogHandler(String filePath) {
        this.filePath = filePath;
    }

    @Override
    protected boolean handleMessage(LogMessage message) {
        // In production: append to file
        return false; // pass to next as well
        // Or return true to stop chain after writing to file.
    }
}
