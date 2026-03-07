package lld.ttl_hashmap;

public class TTLHashMapTest {
    public static void main(String[] args) {
        TTLHashMap<String, String> ttlHashMap = new TTLHashMap<>(1000);
        ttlHashMap.put("ramit", "hansda", 1000);
        System.out.println(ttlHashMap.get("ramit"));
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.println(ttlHashMap.get("ramit"));
    }
}
