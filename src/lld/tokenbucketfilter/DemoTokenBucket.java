package lld.tokenbucketfilter;

public class DemoTokenBucket {

    public static void main(String[] args) {
        RateLimiter tokenBucketFilter = new TokenBucketFilter(5);

        for (int i=0;i<20;i++){
            Thread t1 = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        tokenBucketFilter.getToken();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            t1.start();
        }
    }
}
