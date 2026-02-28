# External Sort — 100GB Integer File (One Integer per Line)

Design for **sorting a 100GB file of integers** (one per line) when the data cannot fit in memory. The result is written to an output file.

---

## How to Approach This in an Interview

### 1. Clarify (30–60 seconds)

Before diving into the algorithm, lock down the problem so your solution matches what they want.

| Ask | Why |
|-----|-----|
| **Format:** One integer per line, text? Any delimiter? | Drives how you read (line-by-line vs binary). |
| **Memory:** How much RAM can I assume? (e.g. 2GB, 8GB) | Determines chunk size and number of temp files. |
| **Output:** Same format (one per line)? Same file or new file? | Affects whether you overwrite or write to a new path. |
| **Stability / duplicates:** Are duplicates allowed? Stable sort required? | For integers usually “any correct order”; clarify if they care. |
| **Invalid lines:** Skip, error, or fail the whole job? | Defines robustness (skip is common). |

**What to say:** *“So we have a 100GB file, one integer per line, and we can’t load it all in memory—I’ll assume we have a few GB for the sort. I’ll design for reading line-by-line, sorting, and writing to a new file in the same format.”*

### 2. State the high-level idea (30 seconds)

Show you know the standard approach: **external merge sort**.

**What to say:** *“We can’t load 100GB into RAM, so we use external sort: split the file into chunks that fit in memory, sort each chunk and write it to a temp file, then merge all those sorted files into one output. That way we only need memory for one chunk plus a small structure for the merge.”*

### 3. Walk through the two phases

**Phase 1 — Split + sort**

- Read the input in chunks of **N** integers (N chosen so N × 8 bytes, or N × size of one number, fits in RAM).
- Sort each chunk in memory (e.g. `Arrays.sort`).
- Write each sorted chunk to a **temp file** (chunk_0, chunk_1, …).

**Phase 2 — K-way merge**

- We have K sorted files. Open all K (with buffered I/O).
- Use a **min-heap** (priority queue) of size K. Each entry is (value, which file it came from).
- Fill the heap with the first value from each file.
- **Loop:** Pop the min, write it to the output, then read the next value from that same file and push it into the heap. If that file is exhausted, don’t push. Stop when the heap is empty.

**What to say:** *“So phase 1 gives us K sorted temp files. In phase 2 we do a K-way merge with a min-heap: we always have the current smallest from each file in the heap, pop the global min, write it, and refill from that file. That produces the fully sorted stream.”*

### 4. Complexity (if they ask)

- **Time:** O(N log N) comparisons, but I/O dominates—effectively two full passes over the data (write chunks, then read/write in merge).
- **Space:** O(chunk size) in phase 1; O(K) for the heap + one buffer per file in phase 2.
- **Disk:** ~100GB temp files + ~100GB output (or reuse/overwrite if they allow).

### 5. Edge cases and follow-ups to mention

- **Empty file / no lines** → 0 chunks; output empty file.
- **One chunk** → After phase 1 you have one sorted file; can just rename/move it to output instead of a full merge.
- **Very large K** (e.g. 10,000 chunks) → Too many open file handles. Say: *“We’d do multi-level merge: merge 100 at a time into a new set of files, then merge those, until we have one file.”*
- **Duplicates** → Min-heap and sort handle them; no extra work.
- **Production:** Mention buffered I/O, optional compression for temp files, and cleaning up temp files when done.

### 6. If they ask you to code

- Start with **phase 1**: read lines, parse to integers, fill a list/array up to chunk size, sort, write to a temp file; loop.
- Then **phase 2**: open all chunk files, min-heap of (value, file index), loop pop-min → write → refill from that file.
- Use clear names (`readChunk`, `mergeSortedFiles`), handle empty input and one-chunk case, and say how you’d test (e.g. small file with known integers, assert output is sorted).

Keeping this structure—**clarify → high-level idea → two phases → edge cases**—shows clear thinking and matches how Staff-level candidates are expected to approach design/coding problems.

---

## 1. Problem

- **Input:** File(s) totaling ~100GB, each line = one integer (e.g. text format).
- **Output:** Single file with the same integers in sorted order (e.g. ascending), one per line.
- **Constraint:** Cannot load 100GB into RAM; assume limited memory (e.g. 2–8GB usable for the sort).

---

## 2. Approach: External Merge Sort

Use **external merge sort**: split into chunks that fit in memory, sort each chunk, then merge.

```
┌─────────────────────────────────────────────────────────────────┐
│  Phase 1: SPLIT + SORT                                           │
│  Read input in chunks of size ≤ M (e.g. 1–2GB)                   │
│  Sort each chunk in memory → write to temp file (chunk_0, …)     │
└─────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│  Phase 2: K-WAY MERGE                                            │
│  Open all sorted chunk files; use a min-heap (priority queue)    │
│  Repeatedly take smallest element, write to output, refill from  │
│  the same file. Continue until all chunks are exhausted.         │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Phase 1: Chunked Read and Sort

**Important: the big file is never fully loaded into RAM.** We read the input in a **streaming** way: open the file with a reader, read one line at a time, parse the integer and add it to an in-memory buffer (e.g. `long[]` of size `chunkSize`). When the buffer is full, we sort it, write it to a temp file, and **clear/reuse the buffer**. Then we continue reading the next lines from the same open file. At any moment, RAM holds only **(1) the current chunk buffer** (e.g. 1–2GB) and **(2) a small read buffer** for the file. The rest of the 100GB stays on disk and is read sequentially.

### What is the buffer? How does “streaming” actually work?

There are **two different buffers** involved:

| What | What it actually is | Who owns it |
|------|----------------------|-------------|
| **Chunk buffer** | An array in our program that holds **integers** we’ve parsed so far for the current chunk (e.g. `long[] buffer` of size 1M). We fill it line by line, then sort it and write it to a temp file. | Our code (e.g. `long[] buffer = new long[chunkSize]`). |
| **Read buffer (I/O buffer)** | A small byte array inside `BufferedReader` (e.g. 8KB in Java). The OS reads a **block of bytes** from the file into this array; `readLine()` gives us one line from those bytes. When used up, the reader reads the **next** block from disk. | The library (`BufferedReader`). |

**How the file is “streamed”:**

- We **never** call “load entire file into memory.” We open the file and get a **reader** that talks to the OS.
- Each time we call `readLine()`, the reader may need more data. It asks the OS to read the **next block** of bytes from disk (e.g. 8KB) into its internal byte buffer. The OS reads from the file **sequentially**.
- We get **one line** (a String). We parse it to an integer and **append** it to our **chunk buffer** (the `long[]`).
- So the flow is: **Disk (100GB file) → OS reads a small block → BufferedReader’s 8KB buffer → we get one line → we store one integer in our chunk buffer.** When the 8KB is exhausted, the reader fetches the next 8KB from disk. The 100GB file is never in RAM in one piece—only a small window (e.g. 8KB) plus our chunk of parsed integers (e.g. 1–2GB).

In short: **buffer** = our array of integers for one chunk. **Streaming** = the reader/OS load the file in small blocks; we process one line at a time and put numbers into the chunk buffer until it’s full, then sort, write, and reuse it.

| Step | Action |
|------|--------|
| 1 | Choose **chunk size** so that one chunk fits in RAM (e.g. 1–2GB of integers). |
| 2 | Read input line-by-line (or in buffers), parsing integers, until you’ve collected `chunkSize` integers (or hit EOF). |
| 3 | Sort the chunk in memory (e.g. `Arrays.sort` for `long[]` or `int[]`). |
| 4 | Write the sorted chunk to a **temporary file**. Clear the buffer (reuse for next chunk). |
| 5 | Repeat from step 2 with the same buffer until the entire input is processed. |

**Chunk size (rough):**

- 100GB of “one integer per line” → depends on digit count. Assume ~10–15 bytes per line on average → on the order of **~10⁹ lines**.
- If each number is stored as `long` (8 bytes), 2GB RAM → ~250M integers per chunk → **~4–40 chunks** depending on line length vs binary.

So we get **K** sorted temporary files (K = number of chunks).

---

## 4. Phase 2: K-Way Merge

| Step | Action |
|------|--------|
| 1 | Open all K sorted chunk files (buffered readers). |
| 2 | Initialize a **min-heap** of size K. Each heap entry = (value, sourceFileId). |
| 3 | Fill the heap with the first value from each file. |
| 4 | **Loop:** Extract min from heap → write to output file. Refill that slot from the same chunk file (next line). If that file is exhausted, don’t add a new entry. |
| 5 | Stop when the heap is empty (all chunks consumed). |
| 6 | Close all readers and the output file; optionally delete temp chunk files. |

**Time:** One pass over the input (Phase 1) + one pass over the combined size (Phase 2). **Space:** O(K) for the heap + one buffer per open file; total dominated by chunk size in Phase 1.

---

## 5. Design Choices

| Topic | Choice | Reason |
|-------|--------|--------|
| **Chunk size** | Configurable, ≤ available RAM | Must fit in memory for in-memory sort. |
| **Temp files** | On same disk or fast SSD | Avoids network; merge is I/O bound. |
| **Number format** | Parse as `long` | Handles full range of 64-bit integers; use `BigInteger` only if needed. |
| **Invalid lines** | Skip or fail | Define policy (e.g. skip non-integer lines, count errors). |
| **Stability** | N/A | Integers: no notion of stable sort needed. |
| **Encoding** | UTF-8 / ASCII | One integer per line; same for output. |

---

## 6. Complexity (High Level)

- **Time:** O(N log N) for comparisons, but I/O dominates: **O(N)** reads + **O(N)** writes in two main passes (chunk write + merge read/write).
- **Space (RAM):** O(chunk size) in Phase 1; O(K) for merge heap + file buffers in Phase 2.
- **Disk:** ~100GB temp files + ~100GB output (or merge in place to overwrite if acceptable).

---

## 7. Possible Extensions

- **Multiple merge passes:** If K is very large (e.g. 10,000 chunks), merge in tiers (e.g. 100-way merge repeatedly) to avoid opening too many file handles.
- **Compression:** Compress temp chunks to reduce disk I/O at the cost of CPU.
- **Parallelism:** Sort multiple chunks in parallel (multiple threads/processes) in Phase 1; single-threaded or multi-threaded merge with care for I/O.
- **Binary I/O:** Store temp files as binary (e.g. `long` per record) to reduce parsing and disk size.

---

## 8. Summary

1. **Split** the 100GB input into memory-sized chunks.
2. **Sort** each chunk in memory and write to temp files.
3. **K-way merge** the temp files using a min-heap into the final output file.

This keeps memory bounded and completes in a small number of sequential and merge passes over the data.
