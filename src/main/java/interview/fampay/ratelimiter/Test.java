package interview.fampay.ratelimiter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.Thread.sleep;

public class Test {
    public static void main(String[] args) {

        TokenBucketRateLimiter ratelimiter = new TokenBucketRateLimiter(2);
        ExecutorService executorService = Executors.newFixedThreadPool(5);
        for (int i=0;i<20;i++) {
            executorService.submit(()->{
                task(ratelimiter);
            }
            );
        }
        try {
            sleep(10000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Taking a break");
        for (int i=0;i<20;i++) {
            executorService.submit(()->{
                        task(ratelimiter);
                    }
            );
        }
    }

    public static void task(TokenBucketRateLimiter ratelimiter){
        boolean flag = ratelimiter.request("user1");
        System.out.println("accepted flag "+ flag+" time " + System.currentTimeMillis());
    }
}


/**
 *
 * rate limiter
 * one method to accept request (userid)
 *
 *
 *
 *
 *
 * **/

