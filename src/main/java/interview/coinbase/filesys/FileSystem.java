package interview.coinbase.filesys;

import java.util.*;

public class FileSystem {
    private Trie root;
    FileSystem(){
        this.root = new Trie();
    }

    public void mkdir(String path){
        root.insert(path, false);
    }

    public void createFile(String path){
        root.insert(path, true);
    }

    public List<Trie> ls(String path){
        List<Trie> result = new ArrayList<>();
        Trie searchedVal =  root.search(path);
        if(searchedVal == null)
            return result;

        if (searchedVal.isFile){
            result.add(searchedVal);
            return result;
        }

        for (Map.Entry<String,Trie > entry: searchedVal.children.entrySet()){
            result.add(entry.getValue());
        }

        // i wanted to sort the result based on
        Collections.sort(result, Comparator.comparingInt((Trie t)-> t.size).reversed());

        return result;


    }

}
