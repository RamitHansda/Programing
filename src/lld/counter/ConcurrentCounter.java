package lld.counter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public class ConcurrentCounter {
 private AtomicInteger counter1 = new AtomicInteger(0);
//
//    public int get(){
//        return counter.get();
//    }
//
//    public void increment(){
//        counter.getAndIncrement();
//    }

    private final LongAdder counter = new LongAdder();

    public void increment() {
        counter.increment();
    }

    public long get() {
        return counter.sum();
    }
}
