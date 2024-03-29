package hashmap;

import java.util.Objects;

public class Entry<K,V> {
    private final K key;
    private V value;
    private Entry<K,V> next;

    public Entry(K key, V value, Entry<K, V> next) {
        this.key = key;
        this.value = value;
        this.next = next;
    }

    public K getKey() {
        return key;
    }

    public V getValue() {
        return value;
    }

    public void setValue(V value) {
        this.value = value;
    }

    public Entry<K, V> getNext() {
        return next;
    }

    public void setNext(Entry<K, V> next) {
        this.next = next;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Entry)) return false;
        Entry<?, ?> entry = (Entry<?, ?>) o;
        return Objects.equals(getKey(), entry.getKey()) &&
                Objects.equals(getValue(), entry.getValue()) &&
                Objects.equals(getNext(), entry.getNext());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getKey(), getValue(), getNext());
    }

    @Override
    public String toString() {
        return "Entry{" +
                "key=" + key +
                ", value=" + value +
                ", next=" + next +
                '}';
    }
}
