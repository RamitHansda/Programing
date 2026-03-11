package interview.fampay.ratelimiter;

public interface RateLimiter {
    boolean request(String userId);
}
