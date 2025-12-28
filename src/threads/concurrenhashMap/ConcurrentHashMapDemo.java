package threads.concurrenhashMap;


import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

class ConcurrentHashMapDemo {

    public static void main( String args[] ) throws Exception {
        ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
        map.put("Biden", 0);

        ExecutorService es = Executors.newFixedThreadPool(5);

        // create a task to increment the vote count
        Runnable task = new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 100; i++)
                    map.compute("Biden", (key, value)->value+1);
            }
        };

        // submit the task twice
        Future future1 = es.submit(task);
        Future future2 = es.submit(task);

        // wait for the threads to finish
        future1.get();
        future2.get();

        // shutdown the executor service
        es.shutdown();

        System.out.println("votes for Biden = " + map.get("Biden"));
    }
}