package lld.logger;

import java.util.List;

public class Logger {
    List<LogAppender> appenderList;
    protected final LogLevel minLevel;
    Logger(LogLevel minLevel, List<LogAppender> appenderList){
        this.appenderList = appenderList;
        this.minLevel = minLevel;
    }

    public void log(LogLevel logLevel, String message){
        if (logLevel.priority<this.minLevel.priority) return;

        LogMessage logMessage = new LogMessage(logLevel, message);
        for (LogAppender appender: appenderList){
            appender.append(logMessage);
        }

    }

    public void debug(String msg) { log(LogLevel.DEBUG, msg); }
    public void info(String msg)  { log(LogLevel.INFO, msg); }
    public void warn(String msg)  { log(LogLevel.WARNING, msg); }
    public void error(String msg) { log(LogLevel.ERROR, msg); }

    public void shutdown() {
        //
    }


}
