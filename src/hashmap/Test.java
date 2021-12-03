package hashmap;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class Test {
    @org.junit.jupiter.api.Test
    public void testMyMap() {

        String str = "raaamiiit";
        String sorted = Stream.of(str.split("")).sorted().collect(Collectors.joining());
        System.out.println(sorted);
        String[] arr = str.split(" ");
        System.out.println(arr.length);
        MyHashMap<String, String> myMap = new MyHashMap<>();
        myMap.put("USA", "Washington DC");
        myMap.put("Nepal", "Kathmandu");
        myMap.put("India", "New Delhi");
        myMap.put("Australia", "Sydney");

        assertNotNull(myMap);
        assertEquals(4, myMap.getSize());
        assertEquals("Kathmandu", myMap.get("Nepal"));
        assertEquals("Sydney", myMap.get("Australia"));

        int res = 0 | 11;
        System.out.println(res);
        if( (0 & 16) == 16) {
            System.out.println("its one");
        }else{
            System.out.println("not");
        }
    }
}
