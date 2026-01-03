package threads.atomicInteger.AtomicFloat;

import sun.misc.Unsafe;

import java.util.concurrent.atomic.AtomicInteger;

public class AtomicFloat extends Number {

    private AtomicInteger floatRepresentation = new AtomicInteger(0);
    private static final Unsafe U = Unsafe.getUnsafe();

    AtomicFloat(float value) {
        floatRepresentation.set(Float.floatToIntBits(value));
    }

    @Override
    public int intValue() {
        return (int) floatValue();
    }

    @Override
    public long longValue() {
        return (long) floatValue();
    }

    @Override
    public float floatValue() {
        return Float.intBitsToFloat(floatRepresentation.get());
    }

    @Override
    public double doubleValue() {
        return (double) floatValue();
    }

    public boolean compareAndSet(float expected, float newValue) {
        return floatRepresentation.compareAndSet(Float.floatToIntBits(expected), Float.floatToIntBits(newValue));
    }

    public float getAndSet(float newValue) {
        int oldBits = floatRepresentation.getAndSet(Float.floatToIntBits(newValue));
        return Float.intBitsToFloat(oldBits);
    }

    public float getAndAdd(float delta) {
        int currentVal;
        int newVal;
        float oldValue;
        do {
            currentVal = floatRepresentation.get();
            oldValue = Float.intBitsToFloat(currentVal);
            newVal = Float.floatToIntBits(oldValue + delta);
        } while (!floatRepresentation.compareAndSet(currentVal, newVal));

        // Return the value before the addition
        return oldValue;
    }

    // Add a method to add and get to return the new value
    public float addAndGet(float delta) {
        int currentVal;
        int newVal;
        float newValue;
        do {
            currentVal = floatRepresentation.get();
            newValue = Float.intBitsToFloat(currentVal) + delta;
            newVal = Float.floatToIntBits(newValue);
        } while (!floatRepresentation.compareAndSet(currentVal, newVal));

        return newValue;
    }
}
