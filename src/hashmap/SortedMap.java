package hashmap;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class SortedMap {
    public static void main(String[] args) {
        HashMap<Integer, Integer> map= new HashMap<Integer,Integer>();
        map.put(1,1);
        map.put(3,2);
        map.put(2,5);
        map.put(4,2);
        LinkedHashMap<Integer, Integer> sortedMap =
                map.entrySet().stream().
                        sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).
                        collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                (e1, e2) -> e1, LinkedHashMap::new));
        int mostFirst = -100;
        int mostSecond = -100;
        for (Map.Entry<Integer, Integer> entry : sortedMap.entrySet()){
            if (mostFirst == -100){
                mostFirst = entry.getKey();
            }else if(mostSecond == -100){
                mostSecond = entry.getKey();
            }
            System.out.println("key " +entry.getKey() + " value " + entry.getValue());
        }
        System.out.println("mostFirst " +mostFirst + " mostSecond " + mostSecond);
    }
}
