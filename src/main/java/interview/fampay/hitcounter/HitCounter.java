package interview.fampay.hitcounter;


import java.util.concurrent.atomic.AtomicReferenceArray;

public class HitCounter {

    private static class Bucket{
        long timestamp;
        long count;

        Bucket(long timestamp, long count){
            this.timestamp = timestamp;
            this.count = count;
        }

        Bucket incrementCount() {
            return new Bucket(this.timestamp, this.count + 1);
        }


        static Bucket createNew(long timestamp) {
            return new Bucket(timestamp, 1L);
        }
    }

    //private Bucket[] bucketList;
    int size;

    private final AtomicReferenceArray<Bucket> buckets;

    HitCounter(int size){
        this.size = size;
        //bucketList = new Bucket[size];
        buckets = new AtomicReferenceArray<>(size);
        for(int i=0;i<size;i++) {
            //bucketList[i] = new Bucket(0L, 0L);
            buckets.set(i,new Bucket(0L, 0L));
        }
    }

    public void hit(long timestamp){
        int index = findIndex(timestamp);

        while (true){
            Bucket currentBucket= buckets.get(index);
            Bucket newBucket;
            if (currentBucket.timestamp == timestamp){
                newBucket = currentBucket.incrementCount();
            }else{
                newBucket = Bucket.createNew(timestamp);
            }
            if (buckets.compareAndSet(index, currentBucket, newBucket)) {
                // Success! We're done
                return;
            }
        }
    }

    public long getHit(long timestamp){
        long totalHit=0;
        for(int i=0;i<size;i++){
            Bucket bucket = buckets.get(i);
            if(bucket.timestamp >= timestamp-size
                    && bucket.timestamp <= timestamp
            ){
                totalHit += bucket.count;
            }
        }
        return totalHit;
    }

    private int findIndex(long timestamp){
        return (int) timestamp%size;
    }


}
