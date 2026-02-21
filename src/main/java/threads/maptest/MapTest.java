package threads.maptest;


import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

class MapTest {
    public static void main( String args[] ) throws Exception {

        // start executor service
        ExecutorService es = Executors.newFixedThreadPool(5);

        performanceTest(new Hashtable<>(10), "Hashtable", es);
        performanceTest(Collections.synchronizedMap(new HashMap<>(10)), "Collections.synchronized(HashMap)", es);
        performanceTest(new ConcurrentHashMap<>(10), "Concurrent Hash Map", es);

        // shutdown the executor service
        es.shutdown();

    }

    static void performanceTest(Map<String, Integer> map, String mapName, ExecutorService es) throws Exception {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 1000000; i++)
                    map.put("key-" + i, i);
            }
        };

        long start = System.currentTimeMillis();

        Future future1 = es.submit(task);
        Future future2 = es.submit(task);
        Future future3 = es.submit(task);
        Future future4 = es.submit(task);
        Future future5 = es.submit(task);

        // wait for the threads to finish
        future1.get();
        future2.get();
        future3.get();
        future4.get();
        future5.get();

        long end = System.currentTimeMillis();

        System.out.println("Milliseconds taken using " + mapName + ": " + (end - start));
    }
}