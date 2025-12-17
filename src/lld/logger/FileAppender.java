package lld.logger;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class FileAppender implements LogAppender{

    private final PrintWriter writer;

    FileAppender(String filePath) throws IOException {
        this.writer = new PrintWriter(new FileWriter(filePath, true), true);
    }


    @Override
    public synchronized void append(LogMessage message) {
        writer.printf(
                "[%s] [%s] [%s] %s%n",
                message.level,
                message.threadName,
                message.timestamp,
                message.message
        );
    }
}
