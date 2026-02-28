package interview;


class MyStack<T> {
    private Object[] arr;
    private int top;

    public MyStack(int capacity) {
        arr = new Object[capacity];
        top = -1;
    }

    public void push(T value) {
        // Resize if needed
        if (top + 1 == arr.length) {
            resize();
        }
        arr[++top] = value;
    }

    @SuppressWarnings("unchecked")
    public T pop() {
        if (isEmpty()) {
            throw new RuntimeException("Stack underflow");
        }
        T val = (T) arr[top];
        arr[top--] = null; // avoid memory leak
        return val;
    }

    @SuppressWarnings("unchecked")
    public T peek() {
        if (isEmpty()) {
            throw new RuntimeException("Stack is empty");
        }
        return (T) arr[top];
    }

    public boolean isEmpty() {
        return top == -1;
    }

    public int size() {
        return top + 1;
    }

    private void resize() {
        Object[] newArr = new Object[arr.length * 2];
        System.arraycopy(arr, 0, newArr, 0, arr.length);
        arr = newArr;
    }
}
