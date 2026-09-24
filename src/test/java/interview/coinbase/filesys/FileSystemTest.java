package interview.coinbase.filesys;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class FileSystemTest {

    private FileSystem fs;

    @BeforeEach
    void setUp() {
        fs = new FileSystem();
    }

    // ── pwd ──────────────────────────────────────────────────────────────

    @Test
    void pwd_initiallyReturnsRoot() {
        assertEquals("/", fs.pwd());
    }

    // ── mkdir ─────────────────────────────────────────────────────────────

    @Test
    void mkdir_absolutePath_createsDirectory() {
        fs.mkdir("/home");
        fs.mkdir("/home/user");
        List<String> names = names(fs.ls("/home"));
        assertTrue(names.contains("user"));
    }

    @Test
    void mkdir_createsIntermediateDirectories() {
        fs.mkdir("/a/b/c/d");
        List<String> top = names(fs.ls("/a/b/c"));
        assertTrue(top.contains("d"));
    }

    @Test
    void mkdir_relativePath_createsUnderCwd() {
        fs.mkdir("/home/user");
        fs.cd("/home/user");
        fs.mkdir("docs");
        List<String> names = names(fs.ls("/home/user"));
        assertTrue(names.contains("docs"));
    }

    @Test
    void mkdir_relativePathWithDotDot_resolvesCorrectly() {
        fs.mkdir("/home/user");
        fs.mkdir("/home/shared");
        fs.cd("/home/user");
        fs.mkdir("../shared/photos");
        List<String> names = names(fs.ls("/home/shared"));
        assertTrue(names.contains("photos"));
    }

    // ── cd ────────────────────────────────────────────────────────────────

    @Test
    void cd_absolutePath_changesCwd() {
        fs.mkdir("/home/user");
        fs.cd("/home/user");
        assertEquals("/home/user", fs.pwd());
    }

    @Test
    void cd_relativePath_changesCwd() {
        fs.mkdir("/home/user");
        fs.cd("/home");
        fs.cd("user");
        assertEquals("/home/user", fs.pwd());
    }

    @Test
    void cd_dotDot_movesUpOneLevel() {
        fs.mkdir("/home/user");
        fs.cd("/home/user");
        fs.cd("..");
        assertEquals("/home", fs.pwd());
    }

    @Test
    void cd_dotDot_fromRoot_staysAtRoot() {
        fs.cd("..");
        assertEquals("/", fs.pwd());
    }

    @Test
    void cd_root_alwaysWorks() {
        fs.mkdir("/home/user");
        fs.cd("/home/user");
        fs.cd("/");
        assertEquals("/", fs.pwd());
    }

    @Test
    void cd_dot_staysInCurrentDirectory() {
        fs.mkdir("/home/user");
        fs.cd("/home/user");
        fs.cd(".");
        assertEquals("/home/user", fs.pwd());
    }

    @Test
    void cd_chainedDotDot_resolvesCorrectly() {
        fs.mkdir("/a/b/c");
        fs.cd("/a/b/c");
        fs.cd("../../..");
        assertEquals("/", fs.pwd());
    }

    @Test
    void cd_nonExistentPath_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> fs.cd("/nonexistent"));
    }

    @Test
    void cd_fileTargetThrowsException() {
        fs.mkdir("/home");
        fs.createFile("/home/readme.txt");
        assertThrows(IllegalArgumentException.class, () -> fs.cd("/home/readme.txt"));
    }

    // ── resolvePath ───────────────────────────────────────────────────────

    @Test
    void resolvePath_absolutePath_returnedAsIs() {
        assertEquals("/home/user", fs.resolvePath("/home/user"));
    }

    @Test
    void resolvePath_trailingSlash_normalized() {
        assertEquals("/home/user", fs.resolvePath("/home/user/"));
    }

    @Test
    void resolvePath_dotDotInMiddle_resolved() {
        assertEquals("/home/other", fs.resolvePath("/home/user/../other"));
    }

    @Test
    void resolvePath_relativePath_appendedToCwd() {
        fs.mkdir("/home/user");
        fs.cd("/home/user");
        assertEquals("/home/user/docs", fs.resolvePath("docs"));
    }

    // ── ls ────────────────────────────────────────────────────────────────

    @Test
    void ls_root_returnsTopLevelEntries() {
        fs.mkdir("/home");
        fs.mkdir("/tmp");
        List<String> names = names(fs.ls("/"));
        assertTrue(names.contains("home"));
        assertTrue(names.contains("tmp"));
    }

    @Test
    void ls_file_returnsSingleEntry() {
        fs.mkdir("/home");
        fs.createFile("/home/readme.txt");
        List<Trie> result = fs.ls("/home/readme.txt");
        assertEquals(1, result.size());
        assertEquals("readme.txt", result.get(0).name);
        assertTrue(result.get(0).isFile);
    }

    @Test
    void ls_nonExistentPath_returnsEmpty() {
        List<Trie> result = fs.ls("/ghost");
        assertTrue(result.isEmpty());
    }

    // ── helper ────────────────────────────────────────────────────────────

    private static List<String> names(List<Trie> nodes) {
        return nodes.stream().map(t -> t.name).collect(Collectors.toList());
    }
}
