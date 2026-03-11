package interview.fampay.ratelimiter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Test {

    private static final Logger log = Logger.getLogger(Test.class.getName());

    public static void main(String[] args) throws InterruptedException {
        configureLogging();

        ExecutorService pool = Executors.newFixedThreadPool(5);

        try (TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter(2)) {
            log.info("=== Batch 1: firing 20 requests with bucket size=2 ===");
            submitBatch(pool, rateLimiter, 20);
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);

            log.info("=== Sleeping 2s to let bucket refill ===");
            Thread.sleep(TimeUnit.SECONDS.toMillis(2));

            ExecutorService pool2 = Executors.newFixedThreadPool(5);
            log.info("=== Batch 2: firing 20 requests after refill ===");
            submitBatch(pool2, rateLimiter, 20);
            pool2.shutdown();
            if (!pool2.awaitTermination(10, TimeUnit.SECONDS)) {
                pool2.shutdownNow();
            }
        }
    }

    private static void submitBatch(ExecutorService pool, TokenBucketRateLimiter rateLimiter, int count) {
        for (int i = 0; i < count; i++) {
            pool.submit(() -> {
                boolean accepted = rateLimiter.request("user1");
                System.out.printf("accepted=%-5s  thread=%s%n", accepted, Thread.currentThread().getName());
            });
        }
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        for (var h : root.getHandlers()) root.removeHandler(h);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.INFO);
        handler.setFormatter(new SimpleFormatter());
        root.addHandler(handler);
    }
}
