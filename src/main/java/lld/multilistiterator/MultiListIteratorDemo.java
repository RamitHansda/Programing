package lld.multilistiterator;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Demonstrates sequential and round-robin iterators over multiple lists,
 * including edge-case inputs (empty list, null list, single list, varying sizes).
 */
public class MultiListIteratorDemo {

    public static void main(String[] args) {

        List<List<Integer>> lists = Arrays.asList(
                Arrays.asList(1, 2, 3),
                Arrays.asList(4, 5),
                Arrays.asList(6, 7, 8, 9)
        );

        System.out.println("=== Sequential Iterator ===");
        printAll(new MultiListIterator<>(lists));
        // Expected: 1 2 3 4 5 6 7 8 9

        System.out.println("\n=== Round-Robin Iterator ===");
        printAll(new RoundRobinMultiListIterator<>(lists));
        // Expected: 1 4 6 2 5 7 3 8 9

        // ---- edge cases ----

        System.out.println("\n=== Sequential – with empty sub-list ===");
        List<List<String>> withEmpty = Arrays.asList(
                Arrays.asList("a", "b"),
                Collections.emptyList(),
                Arrays.asList("c")
        );
        printAll(new MultiListIterator<>(withEmpty));
        // Expected: a b c

        System.out.println("\n=== Round-Robin – with empty sub-list ===");
        printAll(new RoundRobinMultiListIterator<>(withEmpty));
        // Expected: a c b

        System.out.println("\n=== Sequential – single list ===");
        printAll(new MultiListIterator<>(List.of(Arrays.asList(10, 20, 30))));
        // Expected: 10 20 30

        System.out.println("\n=== Sequential – all empty lists ===");
        printAll(new MultiListIterator<>(Arrays.asList(
                Collections.emptyList(), Collections.emptyList()
        )));
        // Expected: (nothing)

        System.out.println("\n=== Round-Robin – single element lists ===");
        printAll(new RoundRobinMultiListIterator<>(Arrays.asList(
                List.of(100), List.of(200), List.of(300)
        )));
        // Expected: 100 200 300
    }

    private static <T> void printAll(Iterator<T> iterator) {
        StringBuilder sb = new StringBuilder();
        while (iterator.hasNext()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(iterator.next());
        }
        System.out.println(sb.length() == 0 ? "(empty)" : sb.toString());
    }
}
