package lld.filesystem;

/**
 * Demo of the in-memory file system, focused on {@code cd} / {@code pwd}.
 */
public final class FileSystemDemo {
    public static void main(String[] args) {
        FileSystem fs = new FileSystem();

        fs.mkdir("/home/ramit/docs");
        fs.mkdir("/home/ramit/src");
        fs.write("/home/ramit/docs/readme.txt", "hello filesystem");

        System.out.println("start pwd = " + fs.pwd()); // /

        fs.cd("home");
        System.out.println("cd home → " + fs.pwd()); // /home

        fs.cd("ramit/docs");
        System.out.println("cd ramit/docs → " + fs.pwd()); // /home/ramit/docs
        System.out.println("ls . → " + fs.ls()); // [readme.txt]

        fs.cd("..");
        System.out.println("cd .. → " + fs.pwd()); // /home/ramit
        System.out.println("ls → " + fs.ls()); // [docs, src]

        fs.cd("/home/ramit/src");
        System.out.println("cd /home/ramit/src → " + fs.pwd());

        fs.cd("../docs");
        System.out.println("cd ../docs → " + fs.pwd());
        System.out.println("read readme.txt → " + fs.read("readme.txt"));

        fs.cd("../..");
        System.out.println("cd ../.. → " + fs.pwd()); // /home

        fs.cd("/");
        System.out.println("cd / → " + fs.pwd()); // /
    }

    private FileSystemDemo() {
    }
}
