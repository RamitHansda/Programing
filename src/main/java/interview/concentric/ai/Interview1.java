package interview.concentric.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

public class Interview1 {
    public static void main(String[] args) {
        System.out.println("hello world");
        int[] arr = {1,1,1,2,2,3,4,4,5};
        int[] res = mostKFrequentElements(arr, 1);
        for (int i : res) {
            System.out.println(i);
        }
        //System.out.println(mostKFrequentElements(arr, 2));
    }

    public static int[] mostKFrequentElements(int[] nums, int k){
        if(nums.length==0) return new int[0];
        if(k==0) return new int[0];
        HashMap<Integer, Integer> freqMap = new HashMap<>();
        for (int num : nums) {
            freqMap.put(num, freqMap.getOrDefault(num, 0) + 1);
        }
        PriorityQueue<Map.Entry<Integer,Integer>> minHeap = new PriorityQueue<>((a,b)->{
            return Integer.compare(a.getValue(), b.getValue());
        });
        for (Map.Entry entry : freqMap.entrySet()) {
            if (minHeap.size() >= k) {
                Map.Entry top = minHeap.peek();
//                System.out.println("new");
//                System.out.println("1212rami"+top.getValue());
//                System.out.println("121ramit"+entry.getValue());
                if((int)top.getValue()<(int) entry.getValue()){
                    minHeap.poll();
                    minHeap.add(entry);
                }
            }else {
                minHeap.add(entry);
            }
        }
        System.out.println("size "+ minHeap.size());

        return minHeap.stream().mapToInt(e->(int)e.getKey()).toArray();


    }
}