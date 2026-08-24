package interview.fampay.lrucache;

import java.util.concurrent.*;

public class LRUDemo {
    public static void main(String[] args) {
        LRUCache<String, String> cache = new LRUCache<>(3);
        ExecutorService executorService = Executors.newFixedThreadPool(3);
//        for (int i=0;i<10;i++){
//            executorService.submit(()->{
//                try {
//                    cyclicBarrier.await();
//                } catch (InterruptedException e) {
//                    throw new RuntimeException(e);
//                } catch (BrokenBarrierException e) {
//                    throw new RuntimeException(e);
//                }
//                System.out.println(cache.get("ramit"));
//            });
//            executorService.submit(()->{
//                cache.put("ramit", "qwqw1");
//                cache.put("ramit1", "qwqw");
//            });
//        }
//
//
        CyclicBarrier cyclicBarrier = new CyclicBarrier(3);
        CountDownLatch countDownLatch = new CountDownLatch(3);
        executorService.submit(()->{
            try {
                    cyclicBarrier.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (BrokenBarrierException e) {
                    throw new RuntimeException(e);
                }
            cache.put("ramit", "qwqw");
            countDownLatch.countDown();
        });

        executorService.submit(()->{
            try {
                cyclicBarrier.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (BrokenBarrierException e) {
                throw new RuntimeException(e);
            }
            cache.put("pooja", "dimdim");
            countDownLatch.countDown();
        });

        executorService.submit(()->{
            try {
                cyclicBarrier.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (BrokenBarrierException e) {
                throw new RuntimeException(e);
            }
            cache.put("bhav", "dahha");
            countDownLatch.countDown();
        });

//        cache.put("ramit", "qwqw");
//        cache.put("pooja", "dimdim");
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.println(cache.get("ramit")); // 1
//        cache.put("bhav", "dahha");
        cache.get("pooja");
        cache.put("ramit", "qwqw121");
        cache.put("bhav122", "dahha12");
        System.out.println(cache.get("pooja")); // 1
        cache.put("bhav", "dahha12");
    }
}
