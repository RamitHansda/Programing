package interview.fampay.ratelimiter;

public interface RateLimiter {
    public boolean request(String userId);
}
