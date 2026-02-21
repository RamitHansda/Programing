package threads.stack.threadsafe;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;

public class LockFreeStack<T> {

    private static class Node<T> {
        final T value;
        Node<T> next;
        Node(T value) { this.value = value; }
    }

    private final AtomicStampedReference<Node<T>> head = new AtomicStampedReference<>(null,1);

    public void push(T item){
        Node<T> newNode = new Node<>(item);
        while (true){
            Node<T> currentNode = head.getReference();
            int currHeadStamp = head.getStamp();
            newNode.next = currentNode;
            if(head.compareAndSet(currentNode, newNode, currHeadStamp, currHeadStamp+1 )){
                return;
            }
        }
    }

    public T pop(){
        while (true){
            Node<T> current = head.getReference();
            int currRefStamp = head.getStamp();
            if (current == null) return null;
            Node<T> next = current.next;
            if(head.compareAndSet(current, next, currRefStamp, currRefStamp+1)){
                return current.value;
            }
        }
    }
}