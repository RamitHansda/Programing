package interview.fampay.ratelimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TokenBucketRateLimiter implements RateLimiter {

    private static class TokenBucket{
        private int availableToken;
        private long lastRefillTime;

        public TokenBucket(int tokens, long lastRefillTime){
            this.availableToken = tokens;
            this.lastRefillTime = lastRefillTime;
        }
    }

    private int maxNumberOfToken;
    private final long refillPeriodMs;
    private ConcurrentHashMap<String, TokenBucket> userBuckets;

    public TokenBucketRateLimiter(
            int tokenSize
    ){
        this(tokenSize, TimeUnit.SECONDS.toMillis(1));
    }

    public TokenBucketRateLimiter(
            int tokenSize, long refillPeriodMs
    ){
        this.maxNumberOfToken = tokenSize;
        this.userBuckets = new ConcurrentHashMap<>();
        this.refillPeriodMs = refillPeriodMs;
    }


    @Override
    public boolean request(String userId) {
        TokenBucket bucket = userBuckets.computeIfAbsent(userId, k -> new TokenBucket(this.maxNumberOfToken, System.currentTimeMillis()));
        synchronized (bucket){
            refill(bucket);
            if(bucket.availableToken>0){
                bucket.availableToken--;
                return true;
            }
            return false;
        }
    }

    private void refill(TokenBucket bucket) {
        long currentTime = System.currentTimeMillis();
        long timeElapsed = currentTime - bucket.lastRefillTime;

        if (timeElapsed >= refillPeriodMs) {
            // Calculate number of periods elapsed and tokens to add
            long periodsElapsed = timeElapsed / refillPeriodMs;
            int tokensToAdd = (int) (periodsElapsed * this.maxNumberOfToken);

            // Update bucket
            bucket.lastRefillTime = currentTime;
            bucket.availableToken = Math.min(bucket.availableToken + tokensToAdd, maxNumberOfToken);
        }
    }

}
