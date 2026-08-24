package lld.designpatterns.chainofresponsibility;

import java.util.Objects;

public final class FileLogHandler extends LogHandler {

    private final String filePath;

    public FileLogHandler(String filePath) {
        this.filePath = Objects.requireNonNull(filePath, "filePath");
    }

    @Override
    protected boolean handleMessage(LogMessage message) {
        // In production: append to filePath
        System.out.println("[FILE:" + filePath + "] " + message.level() + " " + message.text());
        return false; // written; pass to next
    }
}
