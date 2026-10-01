package interview.coinbase.filesys;

import java.util.List;

public class FileSystemDemo {
    public static void main(String[] args) {
        FileSystem fs = new FileSystem();

        // ── mkdir ──────────────────────────────────────────────────────────
        // Creates intermediate directories automatically (like mkdir -p)
        fs.mkdir("/home");
        fs.mkdir("/home/user");
        fs.mkdir("/home/user/documents");
        fs.mkdir("/home/user/downloads");
        System.out.println("ls /home/user -> " + names(fs.ls("/home/user")));
        // Expected: [documents, downloads]

        // ── pwd ────────────────────────────────────────────────────────────
        System.out.println("pwd -> " + fs.pwd());   // /

        // ── cd (absolute path) ─────────────────────────────────────────────
        fs.cd("/home/user");
        System.out.println("cd /home/user  |  pwd -> " + fs.pwd());   // /home/user

        // ── mkdir with relative path ───────────────────────────────────────
        fs.mkdir("projects");
        fs.mkdir("projects/myapp");
        System.out.println("ls /home/user -> " + names(fs.ls("/home/user")));
        // Expected: [documents, downloads, projects]

        // ── cd with relative path ──────────────────────────────────────────
        fs.cd("projects");
        System.out.println("cd projects    |  pwd -> " + fs.pwd());   // /home/user/projects

        // ── cd with .. ────────────────────────────────────────────────────
        fs.cd("..");
        System.out.println("cd ..          |  pwd -> " + fs.pwd());   // /home/user

        // ── cd to root ────────────────────────────────────────────────────
        fs.cd("/");
        System.out.println("cd /           |  pwd -> " + fs.pwd());   // /

        // ── files ─────────────────────────────────────────────────────────
        fs.createFile("/home/user/documents/resume.txt");
        fs.createFile("/home/user/documents/notes.txt");
        System.out.println("ls /home/user/documents -> " + names(fs.ls("/home/user/documents")));

        // ── cd: error – path does not exist ───────────────────────────────
        try {
            fs.cd("/nonexistent");
        } catch (IllegalArgumentException e) {
            System.out.println("Error: " + e.getMessage());
        }

        // ── cd: error – target is a file ──────────────────────────────────
        try {
            fs.cd("/home/user/documents/resume.txt");
        } catch (IllegalArgumentException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private static java.util.List<String> names(List<Trie> nodes) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Trie t : nodes) out.add(t.name);
        java.util.Collections.sort(out);
        return out;
    }
}
