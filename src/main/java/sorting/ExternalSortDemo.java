package sorting;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Demo for {@link ExternalSort}: generates a small file of random integers,
 * runs external sort, and verifies the output is sorted.
 */
public final class ExternalSortDemo {

    public static void main(String[] args) throws IOException {
        Path input = Paths.get("external_sort_input.txt");
        Path output = Paths.get("external_sort_output.txt");
        int numLines = 50_000;
        int chunkSize = 10_000;

        System.out.println("Generating " + numLines + " random integers...");
        generateRandomIntegers(input, numLines);

        ExternalSort sorter = new ExternalSort(chunkSize, Paths.get("."), true);
        sorter.sort(input, output);

        System.out.println("Verifying output is sorted...");
        boolean sorted = verifySorted(output);
        System.out.println("Output sorted: " + sorted);

        // Cleanup demo files
        Files.deleteIfExists(input);
        Files.deleteIfExists(output);
        System.out.println("Done.");
    }

    static void generateRandomIntegers(Path path, int count) throws IOException {
        Random r = new Random(42);
        try (var w = Files.newBufferedWriter(path)) {
            for (int i = 0; i < count; i++) {
                w.write(Long.toString(r.nextLong()));
                w.newLine();
            }
        }
    }

    static boolean verifySorted(Path path) throws IOException {
        try (var r = Files.newBufferedReader(path)) {
            long prev = Long.MIN_VALUE;
            String line;
            while ((line = r.readLine()) != null) {
                long curr = Long.parseLong(line.trim());
                if (curr < prev) return false;
                prev = curr;
            }
        }
        return true;
    }
}
