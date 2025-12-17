package lld.logger;


import java.io.IOException;
import java.util.Arrays;

class LoggerFactory {

    public static Logger createSyncLogger() {
        return new Logger(
                LogLevel.INFO,
                Arrays.asList(new ConsoleAppender())
        );
    }

    public static Logger createAsyncLogger() {
        try {
            return new AsyncLogger(
                    LogLevel.DEBUG,
                    Arrays.asList(new ConsoleAppender(), new FileAppender("/Users/ramithansda/WorkSpace/learning/Programing/src/lld/logger/ramit.text"))
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
