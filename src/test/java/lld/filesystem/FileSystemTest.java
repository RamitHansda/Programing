package lld.filesystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemTest {
    private FileSystem fs;

    @BeforeEach
    void setUp() {
        fs = new FileSystem();
        fs.mkdir("/home/user/docs");
        fs.mkdir("/home/user/src");
        fs.mkdir("/var/log");
        fs.write("/home/user/docs/notes.txt", "alpha");
    }

    @Test
    void pwdStartsAtRoot() {
        assertEquals("/", fs.pwd());
    }

    @Test
    void cdAbsolutePathUpdatesPwd() {
        fs.cd("/home/user");
        assertEquals("/home/user", fs.pwd());
        assertEquals(List.of("docs", "src"), fs.ls());
    }

    @Test
    void cdRelativePathFromCwd() {
        fs.cd("home");
        assertEquals("/home", fs.pwd());
        fs.cd("user/docs");
        assertEquals("/home/user/docs", fs.pwd());
        assertEquals(List.of("notes.txt"), fs.ls());
    }

    @Test
    void cdDotIsNoOp() {
        fs.cd("/home/user");
        fs.cd(".");
        assertEquals("/home/user", fs.pwd());
    }

    @Test
    void cdDotDotMovesToParent() {
        fs.cd("/home/user/docs");
        fs.cd("..");
        assertEquals("/home/user", fs.pwd());
        fs.cd("../..");
        assertEquals("/", fs.pwd());
    }

    @Test
    void cdDotDotAtRootStaysAtRoot() {
        fs.cd("..");
        assertEquals("/", fs.pwd());
        fs.cd("../../.");
        assertEquals("/", fs.pwd());
    }

    @Test
    void cdMixedRelativeWithDotAndDotDot() {
        fs.cd("/home/user/docs");
        fs.cd("../src/./../docs");
        assertEquals("/home/user/docs", fs.pwd());
    }

    @Test
    void cdFailsWhenPathMissing() {
        FileSystemException ex = assertThrows(FileSystemException.class, () -> fs.cd("/missing"));
        assertTrue(ex.getMessage().contains("no such file or directory"));
    }

    @Test
    void cdFailsWhenTargetIsFile() {
        FileSystemException ex = assertThrows(FileSystemException.class,
                () -> fs.cd("/home/user/docs/notes.txt"));
        assertTrue(ex.getMessage().contains("not a directory"));
    }

    @Test
    void lsAndReadAreRelativeToCwd() {
        fs.cd("/home/user/docs");
        assertEquals(List.of("notes.txt"), fs.ls("."));
        assertEquals("alpha", fs.read("notes.txt"));
        fs.write("notes.txt", "-beta");
        assertEquals("alpha-beta", fs.read("./notes.txt"));
    }

    @Test
    void mkdirRelativeToCwd() {
        fs.cd("/home/user");
        fs.mkdir("bin");
        assertEquals(List.of("bin", "docs", "src"), fs.ls());
        fs.cd("bin");
        assertEquals("/home/user/bin", fs.pwd());
    }

    @Test
    void mkdirCreatesParents() {
        fs.mkdir("/a/b/c");
        fs.cd("/a/b/c");
        assertEquals("/a/b/c", fs.pwd());
    }

    @Test
    void touchAndRmFile() {
        fs.cd("/home/user/docs");
        fs.touch("todo.txt");
        assertEquals(List.of("notes.txt", "todo.txt"), fs.ls());
        fs.rm("todo.txt");
        assertEquals(List.of("notes.txt"), fs.ls());
    }

    @Test
    void rmCwdResetsToRoot() {
        fs.mkdir("/tmp/empty");
        fs.cd("/tmp/empty");
        fs.rm(".");
        // "." resolves to cwd; removing cwd directory should fail or reset.
        // Our rm(".") resolves to /tmp/empty — after delete, cwd resets.
        assertEquals("/", fs.pwd());
    }

    @Test
    void pathNormalizationGoldens() {
        assertEquals("/", Path.resolve(Path.root(), "/a/../b/./c/../..").toAbsolutePath());
        assertEquals("/home/user", Path.resolve(Path.ofAbsolute("/home/user/docs"), "..").toAbsolutePath());
        assertEquals("/x", Path.resolve(Path.ofAbsolute("/home"), "/x").toAbsolutePath());
    }
}
