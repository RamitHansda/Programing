package lld.producer_consumer.waitnotifyall;

public class BlockingQueueWithWaitNotifyAll<T> {
    T[] array;
    int size =0;
    int capacity;
    int head;
    int tail;
    private final Object lock = new Object();

    public BlockingQueueWithWaitNotifyAll(int capacity){
        this.array = (T[]) new Object[capacity];
        this.capacity = capacity;
        this.head = 0;
        this.tail = 0;
    }

    public void enqueue(T item) throws InterruptedException{
        synchronized (lock){
            while (size == capacity){
                lock.wait();
            }

            array[tail] = item;
            tail = (tail+1)%capacity;
            size++;
            lock.notifyAll();
            System.out.println("Enqueued "+ item + " by "+ Thread.currentThread().getName());
        }
    }

    public T dequeue() throws InterruptedException{
        synchronized (lock){
            T item = null;
            while (size == 0){
                lock.wait();
            }
            item = array[head];
            head = (head +1)%capacity;
            size--;
            lock.notifyAll();
            System.out.println("Dequeued "+ item + " by "+ Thread.currentThread().getName());
            return item;
        }
    }
}
