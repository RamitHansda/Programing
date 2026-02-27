package datastructures;

public class StatsHashMapDemo {

    public static void main(String[] args) {
        StatsHashMap<String, Integer> map = new StatsHashMap<>();

        map.put("a", 1);
        map.put("b", 2);
        map.put("c", 3);
        map.put("ab", 10);  // may collide depending on hash

        System.out.println("get(\"a\") = " + map.get("a"));
        System.out.println("get(\"b\") = " + map.get("b"));
        System.out.println("get(\"x\") = " + map.get("x"));  // absent key

        System.out.println("Get calls in last 5 mins: " + map.getGetCountLast5Mins());
        System.out.println("Avg get QPS (last 5 mins): " + map.getAvgQPS());
        System.out.println("size = " + map.size());
    }
}
