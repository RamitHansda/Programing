package threads.stack.threadsafe;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

public class ThreadSafeStack<T> {
    private final Deque<T> stack = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();

    public void push(T item){
        lock.lock();
        try{
            stack.push(item);
        } finally {
            lock.unlock();
        }
    }

    public T pop(){
        lock.lock();
        try {
            return stack.isEmpty()? null : stack.pop();
        } finally {
            lock.unlock();
        }
    }
}
