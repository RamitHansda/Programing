package interview.coinbase.filesys;

import java.util.*;

public class FileSystemDemo {
    public static void main(String[] args) {
        FileSystem fileSystem = new FileSystem();
        fileSystem.mkdir("/ramit/test/test1");
        fileSystem.mkdir("/ramit/test/test12");
        fileSystem.createFile("/ramit/test/ramit.txt");
        fileSystem.createFile("/ramit/test/ramit1.txt");
        System.out.println(fileSystem.ls("/ramit/test/"));
        System.out.println(fileSystem.ls("/"));
        Deque<Integer> deque = new ArrayDeque<>();
        deque.add(12);
        deque.add(10);
        //deque.addLast(11);
        //deque.addFirst(13);
        int first = deque.peekFirst();
        System.out.println("first "+ first);
        int last = deque.peekLast();
        System.out.println("last "+ last);

        TreeMap<Integer, String> map =
                new TreeMap<>((a, b) -> b - a);
        map.put(12, "ramit");
        map.put(15, "stracy");

        List<Map.Entry<Integer, String>> list =
                new ArrayList<>(map.entrySet());

        list.sort(Map.Entry.comparingByValue());
        System.out.println(list);

    }
}
