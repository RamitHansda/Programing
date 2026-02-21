package lld.logger;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

class AsyncLogger extends Logger {

    private final BlockingQueue<LogMessage> queue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;

    private Thread worker;

    AsyncLogger(LogLevel minLevel, List<LogAppender> appenders) {
        super(minLevel, appenders);
        this.running = true;
        startWorker();

    }

    @Override
    public void log(LogLevel level, String message) {
        if (level.priority < minLevel.priority) return;
        queue.offer(new LogMessage(level, message));
    }

    private void startWorker() {
        worker = new Thread(() -> {
            try {
                while (true) {
                    LogMessage msg = queue.take();
                    for (LogAppender appender : appenderList) {
                        appender.append(msg);
                    }
                }
            } catch (InterruptedException ignored) {}
        }, "worker");
        worker.setDaemon(true);
        worker.start();
    }

    public void shutdown() {
        while (!queue.isEmpty())
        this.running = false;
        worker.interrupt();
//        try {
//            worker.join();
//        } catch (InterruptedException ignored) {}
    }
}