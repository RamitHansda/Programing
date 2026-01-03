package threads.atomicInteger.atomicbyte;

import java.util.concurrent.atomic.AtomicInteger;

public class AtomicByte {
    private AtomicInteger byteRepresentation;

    AtomicByte(byte value){
        byteRepresentation = new AtomicInteger(value);
    }


    public void shiftRight(){
        byte currentVal;
        do {
            currentVal = byteRepresentation.byteValue();
        }while (!byteRepresentation.compareAndSet(currentVal, currentVal >>1));

    }

    public void shiftLeft(){
        byte currentVal;
        do{
            currentVal = byteRepresentation.byteValue();
        } while (!byteRepresentation.compareAndSet(currentVal, currentVal<<1));
    }

    public void print() {
        byte currentValue = byteRepresentation.byteValue();
        byte mask = (byte) 0b10000000;

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < 8; i++) {
            sb.append((currentValue & mask) == mask ? "1" : "0");
            currentValue = (byte) (currentValue << 1);
        }

        System.out.println(sb.toString());
    }



}
