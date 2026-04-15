# LLD: In-memory file system

## Interview prompt

Design an **in-memory** hierarchical file system supporting paths, directories, files with content, and operations like create, delete, move, read, list.

## Clarifying questions

- **Paths**: absolute only? `.` and `..`?
- **Symlinks**: in scope?
- **Concurrency**: single-threaded editor vs concurrent API?

## Functional requirements

- `mkdir`, `createFile`, `write`, `read`, `delete`, `move(src, dst)`
- `ls(path)` listing
- Optional: `find(name)` / glob (timeboxed)

## Non-functional requirements

- **Correct path resolution** and cycle prevention for moves.
- Reasonable **memory** representation for file bytes (byte arrays vs rope—keep simple unless asked).

## Design patterns

| Pattern | Role |
|--------|------|
| **Composite** | `Node` interface implemented by `FileNode` and `DirectoryNode`; uniform `getName()`, `getSize()`, `list()`. |
| **Iterator** | Tree traversal without exposing internal collections. |
| **Visitor** (optional) | `SizeVisitor`, `FindVisitor` if many algorithms over the tree. |

## Invariants

- Names unique among siblings; no empty path segments.
- `delete` is recursive for directories (define semantics).
- `move` cannot create cycles (moving `/a` under `/a/b` must fail).

## Staff-level implementation notes

- Keep **path parsing** (`Path` value type) separate from **mutation** operations.
- `Inode` table vs strict tree: for LLD, tree is enough; mention inode indirection if interviewer wants hardlinks.

## Java sketch

```java
public interface Node {
    String name();
    Optional<DirectoryNode> parent();
}

public final class DirectoryNode implements Node {
    private final Map<String, Node> children = new HashMap<>();
    public void addChild(Node n) { /* set parent */ }
}
```

## Concurrency

- `ReadWriteLock` on a subtree or on whole FS for interview simplicity; production uses finer-grained locking or immutable snapshots.

## Testing strategy

- Path resolution golden tests.
- Cycle prevention tests for move.
- Iterator order expectations (lexicographic vs unsorted—state choice).

## Follow-ups

- **Watchers** (inotify-style), quotas, compression—usually out of scope unless staff+.
