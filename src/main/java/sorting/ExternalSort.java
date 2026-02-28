package sorting;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * External sort for very large files of integers (one per line).
 * Uses chunked in-memory sort followed by k-way merge. Suitable for files
 * that do not fit in RAM (e.g. 100GB).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Phase 1: Read input in chunks of at most chunkSize integers; sort each chunk; write to temp files.</li>
 *   <li>Phase 2: K-way merge all temp files using a min-heap into the output file.</li>
 * </ol>
 */
public final class ExternalSort {

    private final int chunkSize;  // max integers per chunk (memory bound)
    private final Path tempDir;
    private final boolean deleteTempFiles;

    public ExternalSort(int chunkSize, Path tempDir, boolean deleteTempFiles) {
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be positive");
        this.chunkSize = chunkSize;
        this.tempDir = tempDir != null ? tempDir : Paths.get(System.getProperty("java.io.tmpdir"));
        this.deleteTempFiles = deleteTempFiles;
    }

    /**
     * Sorts integers from inputPath (one per line) and writes sorted output to outputPath.
     */
    public void sort(Path inputPath, Path outputPath) throws IOException {
        List<Path> chunkFiles = sortIntoChunks(inputPath);
        try {
            mergeChunks(chunkFiles, outputPath);
        } finally {
            if (deleteTempFiles) {
                for (Path p : chunkFiles) {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                }
            }
        }
    }

    /**
     * Phase 1: Read input in chunks, sort in memory, write each chunk to a temp file.
     */
    private List<Path> sortIntoChunks(Path inputPath) throws IOException {
        List<Path> chunkFiles = new ArrayList<>();
        long[] buffer = new long[chunkSize];
        int count = 0;
        int chunkIndex = 0;

        try (BufferedReader reader = Files.newBufferedReader(inputPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    buffer[count++] = Long.parseLong(line);
                } catch (NumberFormatException e) {
                    // Skip invalid lines (configurable: could throw or log)
                    continue;
                }
                if (count == chunkSize) {
                    chunkFiles.add(writeSortedChunk(buffer, count, chunkIndex++));
                    count = 0;
                }
            }
        }
        if (count > 0) {
            chunkFiles.add(writeSortedChunk(buffer, count, chunkIndex));
        }
        return chunkFiles;
    }

    private Path writeSortedChunk(long[] buffer, int count, int chunkIndex) throws IOException {
        Arrays.sort(buffer, 0, count);
        Path chunkPath = tempDir.resolve("external_sort_chunk_" + chunkIndex + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(chunkPath)) {
            for (int i = 0; i < count; i++) {
                writer.write(Long.toString(buffer[i]));
                writer.newLine();
            }
        }
        return chunkPath;
    }

    /**
     * Phase 2: K-way merge of sorted chunk files into output using a min-heap.
     */
    private void mergeChunks(List<Path> chunkFiles, Path outputPath) throws IOException {
        if (chunkFiles.isEmpty()) {
            Files.write(outputPath, Collections.emptyList());
            return;
        }
        if (chunkFiles.size() == 1) {
            Files.move(chunkFiles.get(0), outputPath, StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        int k = chunkFiles.size();
        // Heap entry: (value, index of the chunk file that produced it)
        PriorityQueue<HeapEntry> heap = new PriorityQueue<>(k, Comparator.comparingLong(e -> e.value));
        BufferedReader[] readers = new BufferedReader[k];

        try {
            for (int i = 0; i < k; i++) {
                readers[i] = Files.newBufferedReader(chunkFiles.get(i));
                String line = readers[i].readLine();
                if (line != null && !line.trim().isEmpty()) {
                    heap.add(new HeapEntry(Long.parseLong(line.trim()), i));
                }
            }

            try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
                while (!heap.isEmpty()) {
                    HeapEntry min = heap.poll();
                    writer.write(Long.toString(min.value));
                    writer.newLine();
                    String next = readers[min.sourceIndex].readLine();
                    if (next != null && !next.trim().isEmpty()) {
                        heap.add(new HeapEntry(Long.parseLong(next.trim()), min.sourceIndex));
                    }
                }
            }
        } finally {
            for (BufferedReader r : readers) {
                if (r != null) {
                    try { r.close(); } catch (IOException ignored) { }
                }
            }
        }
    }

    private static final class HeapEntry {
        final long value;
        final int sourceIndex;

        HeapEntry(long value, int sourceIndex) {
            this.value = value;
            this.sourceIndex = sourceIndex;
        }
    }

    // --------------- Convenience ---------------

    /**
     * Example: sort with 1M integers per chunk, temp dir = system default, delete temp files.
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: ExternalSort <input-file> <output-file> [chunk-size]");
            System.err.println("  chunk-size = max integers per chunk (default 1_000_000)");
            return;
        }
        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        int chunkSize = args.length > 2 ? Integer.parseInt(args[2]) : 1_000_000;

        ExternalSort sorter = new ExternalSort(chunkSize, null, true);
        sorter.sort(input, output);
        System.out.println("Sorted output written to: " + output);
    }
}
