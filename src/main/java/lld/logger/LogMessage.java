package lld.logger;

public class LogMessage {
    final LogLevel level;
    final String message;
    final long timestamp;
    final String threadName;

    LogMessage(LogLevel logLevel, String message){
        this.level = logLevel;
        this.message = message;
        this.threadName = Thread.currentThread().getName();
        this.timestamp= System.currentTimeMillis();
    }
}
