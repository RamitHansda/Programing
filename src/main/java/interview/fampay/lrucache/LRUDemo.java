package interview.fampay.lrucache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LRUDemo {
    public static void main(String[] args) {
        LRUCache<String, String> cache = new LRUCache<>(3);
        ExecutorService executorService = Executors.newFixedThreadPool(3);
//        for (int i=0;i<10;i++){
//            executorService.submit(()->{
//                System.out.println(cache.get("ramit"));
//            });
//            executorService.submit(()->{
//                cache.put("ramit", "qwqw1");
//                cache.put("ramit1", "qwqw");
//            });
//        }
        cache.put("ramit", "qwqw");
        cache.put("pooja", "dimdim");
        System.out.println(cache.get("ramit")); // 1
        cache.put("bhav", "dahha");
        cache.put("ramit", "qwqw121");
        cache.put("bhav122", "dahha12");
        System.out.println(cache.get("pooja")); // 1
        cache.put("bhav", "dahha12");
    }
}
