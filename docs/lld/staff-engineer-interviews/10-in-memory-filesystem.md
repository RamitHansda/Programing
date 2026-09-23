# LLD: In-memory file system

## Interview-ready snapshot

**Say first (≈30s):** **Composite** `Node` tree: directories own children; **Path** value for parsing/normalization (`.` / `..`); façade tracks **cwd** for `cd`/`pwd`; move/delete enforce **no cycles**; `FileSystem` over root.

**Default assumptions:** Absolute paths first; single-threaded or RW-lock unless they want fine-grained.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | cwd / `cd`? `..` and `.`; symlinks in or out; concurrent edits. |
| Model | 10 min | Node, File, Directory, Path; parent pointers; cwd. |
| API + flow | 10 min | cd/pwd, mkdir, create, write, read, delete, move flows. |
| Hard | 10 min | Cycle check on move; traversal iterator; locking scope. |
| Close | 5 min | Hardlinks/inodes as extension. |

**Whiteboard order:** (1) Node interface (2) small tree example (3) move cycle bad case (4) Path resolution steps (5) public API.

**Likely probes:** `rm -rf` semantics? Thread safety of list while iterating?

**30s closer:** Composite gives uniform operations; path logic isolated; cycles are explicit invariant checks.

---

## Interview prompt

Design an **in-memory** hierarchical file system supporting paths, directories, files with content, and operations like create, delete, move, read, list.

## Clarifying questions

- **Paths**: absolute only? `.` and `..`?
- **Symlinks**: in scope?
- **Concurrency**: single-threaded editor vs concurrent API?

## Functional requirements

- `cd(path)`, `pwd()` — cwd + relative paths (`.`, `..`); reject file / missing targets
- `mkdir`, `createFile`, `write`, `read`, `delete`, `move(src, dst)`
- `ls(path)` listing
- Optional: `find(name)` / glob (timeboxed)

**Reference implementation:** `src/main/java/lld/filesystem/` (+ `docs/lld/filesystem/README.md`).

## Non-functional requirements

- **Correct path resolution** and cycle prevention for moves.
- Reasonable **memory** representation for file bytes (byte arrays vs rope—keep simple unless asked).

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Composite node** | `Node` (interface) | Common operations: name, parent reference, optional size. |
| **Entity** | `FileNode` | Holds bytes or stream handle; leaf in composite tree. |
| **Aggregate root** | `DirectoryNode` | Child map `name → Node`; owns add/remove/move invariants. |
| **Value object** | `Path` | Normalized absolute path segments; parsing and `..` rules. |
| **Facade** | `FileSystem` | Public API: resolve path → delegate to root directory. |

**Relationships:** `DirectoryNode` **composes** 0..N `Node` children; each `Node` has optional **parent** `DirectoryNode`.

**Not modeled:** permissions ACL matrix, block device storage.

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
