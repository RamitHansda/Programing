# In-memory file system (`cd` / `pwd`)

Interview-oriented Java LLD for a hierarchical file system with a **current working directory**.

## Why `cd` is the interesting part

Absolute-only APIs (`mkdir("/a/b")`) are the LeetCode baseline. Adding shell-style `cd` forces you to:

1. Track **cwd** as a path + resolved directory node
2. Resolve **relative** and **absolute** paths through one function
3. Normalize **`.`** and **`..`** without walking off root
4. Reject `cd` into a **file** or missing path

## API

| Method | Behavior |
|--------|----------|
| `cd(path)` | Change cwd; absolute or relative; supports `.` / `..` |
| `pwd()` | Absolute cwd string |
| `ls()` / `ls(path)` | Sorted names; file path → `[fileName]` |
| `mkdir(path)` | `mkdir -p` semantics |
| `touch` / `write` / `read` | File create / append / read |
| `rm(path)` | Delete file or empty directory |

## Model

- **Composite**: `FsNode` ← `DirectoryNode` / `FileNode`
- **Value object**: `Path` (normalized absolute segments)
- **Facade**: `FileSystem` owns root + cwd

## Run

```bash
mvn -q test -Dtest=lld.filesystem.FileSystemTest
mvn -q -DskipTests compile
java -cp target/classes lld.filesystem.FileSystemDemo
```

## Interview opener (≈30s)

“I’ll model a Composite tree of directories and files, and isolate path resolution in a `Path` value type that understands `.` and `..`. The façade keeps a cwd; every API resolves relative to it. `cd` only succeeds when the resolved node is an existing directory.”
