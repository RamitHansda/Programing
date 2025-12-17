package lld.tokenbucketfilter;

public interface RateLimiter {
    void getToken() throws InterruptedException;
}
