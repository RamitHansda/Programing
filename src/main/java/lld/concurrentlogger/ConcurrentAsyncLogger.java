package lld.concurrentlogger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConcurrentAsyncLogger {
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Thread daemon;
    private final AtomicBoolean running = new AtomicBoolean(true);

    ConcurrentAsyncLogger(){
        this.daemon = new Thread(()->{
            while (running.get()){
                try {
                    String message = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (message!=null){
                        System.out.println(message);
                    }
                } catch (InterruptedException e){
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            String message;
            while ((message = queue.poll())!=null){
                System.out.println(message);
            }
        });
        daemon.setDaemon(true);
        daemon.start();
    }

    public void log(String message){
        if(running.get() && message !=null){
            queue.offer(message);
        }
    }

    public void shutdown() {
        running.set(false);
        this.daemon.interrupt();
        try {
            // Wait for the daemon thread to finish
            daemon.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }

}
