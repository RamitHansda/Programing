package interview.coinbase.filesystem;

import java.util.*;

public class FileSystemDemo {
    public static void main(String[] args) {
        FileSystem fileSystem = new FileSystem();
        fileSystem.ls("/"); // return []
        fileSystem.mkdir("/a/b/c");
        //fileSystem.addContentToFile("/a/b/c/d", "hello");
        System.out.println(fileSystem.ls("/")); // return ["a"]
        //fileSystem.readContentFromFile("/a/b/c/d"); // return "hello"

        //File f1 = new File("ramit.txt", 10);


        Random random = new Random();
        List<File> fileList = new ArrayList<>();
        for (int i=0;i<10;i++){
            String generatedString = random.ints('A', 'Z')
                    .limit(5)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString();
            fileList.add(new File("generatedString"+".txt", random.nextInt(10), random.nextInt(10)));
            fileList.add(new File(generatedString+".txt", random.nextInt(10), random.nextInt(10)));
        }

        fileList.sort(Comparator.comparingInt((File f)-> f.size).reversed().thenComparing((file -> file.name))
                .thenComparing(file -> file.bitSize).reversed());
        System.out.println(fileList);

    }
}

class File{
    String name;
    int size;
    int bitSize;

    File(String name, int size, int bitSize){
        this.name = name;
        this.size = size;
        this.bitSize = bitSize;
    }

    @Override
    public String toString() {
        return name + "(" + size + ") and ( "+ bitSize +" )";
    }
}


class Trie{
    String name;
    int size;
    boolean isFile;
    HashMap<String, Trie> children = new HashMap<>();

    public Trie insert(String path, boolean isFile){
        String[] pathArray = path.split("/");
        Trie current = this;
        for (int i=1;i< pathArray.length;i++){
            String item = pathArray[i];
            current.children.putIfAbsent(item, new Trie());
            current= current.children.get(item);
        }

        current.isFile = isFile;
        if (isFile) {
            current.name = pathArray[pathArray.length - 1];
        }

        return current;

    }


    public Trie search(String key){
        Trie current = this;
        String[] keySplitArray = key.split("/");
        for (String keySplit: keySplitArray){
            if(!current.children.containsKey(keySplit))
                return null;
            current = current.children.get(keySplit);
        }
        return current;
    }

}

class FileSystem{
    Trie root;
    FileSystem(){
        this.root = new Trie();
    }

    public void mkdir(String filePath){
        this.root.insert(filePath, false);
    }

    public List<String> ls(String filePath){
        Trie searchedTrie =  this.root.search(filePath);
        List<String> result = new ArrayList<>();
        if(searchedTrie==null)
            return result;

        if(searchedTrie.isFile){
            result.add(searchedTrie.name);
            return result;
        }
        for(Map.Entry<String, Trie> children: searchedTrie.children.entrySet()){
            result.add(children.getKey());
        }
        Collections.sort(result, (a,b)->{
            return a.compareTo(b);
        });
        return result;
    }
}