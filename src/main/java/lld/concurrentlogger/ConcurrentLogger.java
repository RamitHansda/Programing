package lld.concurrentlogger;


/*
Why this works
    synchronized ensures only one thread logs at a time
    Prevents interleaved output

Why this is NOT ideal
    All threads block on I/O
    Poor scalability
* */
public class ConcurrentLogger {
    public synchronized void log(String message){
        System.out.println(Thread.currentThread().getName() +":"+ message);
    }
}
