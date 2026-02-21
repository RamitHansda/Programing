package lld.logger;

public class ConsoleAppender implements LogAppender {

    @Override
    public void append(LogMessage message) {
        System.out.printf("[%s] [%s] [%s] [%s]\n",
                message.level,
                message.threadName,
                message.timestamp,
                message.message);

    }
}
