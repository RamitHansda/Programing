package lld.tokenbucketfilter;

public class TokenBucketFilter implements RateLimiter {
    private final int MAX_TOKENS;
    private int currentTokens=0;
    private long lastTokenRequestedTime;
    public TokenBucketFilter(int maxToken){
        this.MAX_TOKENS = maxToken;
        this.currentTokens =0;
        this.lastTokenRequestedTime = System.currentTimeMillis();
    }

    public synchronized void getToken() throws InterruptedException{
        refill();
        while  (currentTokens <= 0){
            long elapsed = System.currentTimeMillis() - lastTokenRequestedTime;
            long waitTime = 1000 - elapsed;
            if (waitTime < 0) waitTime = 0;
            this.wait(waitTime);
            refill();
        }
        currentTokens--;
        System.out.println("Given a token by thread "+ Thread.currentThread().getName()+" time " +  System.currentTimeMillis());
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastTokenRequestedTime;

        int tokensToAdd = (int)(elapsed / 100);
        if (tokensToAdd > 0) {
            currentTokens = Math.min(MAX_TOKENS, currentTokens + tokensToAdd);
            lastTokenRequestedTime = now;
        }
    }
}
