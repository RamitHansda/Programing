package threads.boundedbuffer.reentrentlock;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class BoundedBuffer<T> {
    private final Object[] buffer;
    private int head=0;
    private int tail =0;
    private int size =0;
    private final int capacity;
    private ReentrantLock lock;
    private final Condition notEmpty;
    private final Condition notFull;

    BoundedBuffer(int capacity){
        this.buffer = new  Object[capacity];
        this.capacity = capacity;
        this.lock = new ReentrantLock(true);
        this.notEmpty = this.lock.newCondition();
        this.notFull = this.lock.newCondition();
    }

    public void enqueue(T item) throws InterruptedException {
        lock.lock();
        try{
            while (size == capacity){
                notFull.await();   // block producer
            }

            buffer[tail]=item;
            tail = (tail+1)% this.capacity;
            this.size++;
            notEmpty.signal(); // wake one consumer
        } finally {
            lock.unlock();
        }
    }

    public T dequeue() throws InterruptedException {
        lock.lock();
        T item;
        try {
            while (size == 0){
                notEmpty.await();   // block consumer
            }
            item = (T) buffer[head];
            buffer[head] = null;
            head = (head+1)% this.capacity;
            this.size--;
            notFull.signal(); // wake one producer
        } finally {
            lock.unlock();
        }
        return item;
    }
}
