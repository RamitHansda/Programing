package interview.coinbase.indexer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Solution {
    public static void main(String[] args) {
        Indexer indexer = new Indexer();
        indexer.addBlock(List.of(new Txn("init", "acc1", 10), new Txn("init", "acc2", 20)));
        System.out.println(indexer.getBalance("acc1",0));

    }

    static class Indexer {

        HashMap<String, Integer> globalBalance;
        List<Block> blocks;
        Indexer(){
            globalBalance = new HashMap<>();
            blocks = new ArrayList<>();
        }

        public void addBlock(List<Txn> txns){
            Block block = new Block();

            if (block.addBlock(txns, globalBalance)){
                blocks.add(block);
            }
        }

        public int getBalance(String account, int blockId){
            if (blockId>=blocks.size())
                return 0;
            if(blocks.get(blockId).availableBalanceMap.containsKey(account))
                return blocks.get(blockId).availableBalanceMap.get(account);
            else
                return getBalance(account);
        }


        public int getBalance(String account){
            return globalBalance.getOrDefault(account, 0);
        }
    }

    static class Txn {
         String from;
         String to;
         int value;

        Txn(String form, String to, int value){
            this.from = form;
            this.to = to;
            this.value = value;
        }
    }

    static class Block{
        HashMap<String, Integer> availableBalanceMap;
        Block(){
            availableBalanceMap = new HashMap<>();
        }

        public boolean addBlock(List<Txn> txns, HashMap<String, Integer> globalMap){
            for (Txn txn: txns){
                if(txn.value<=0)
                    return false;

                if (txn.from.equals("init")){
                    globalMap.put(txn.to, globalMap.getOrDefault(txn.to, 0)+txn.value);
                    availableBalanceMap.put(txn.to, globalMap.getOrDefault(txn.to, 0));
                }else{
                    String from = txn.from;
                    String to = txn.to;
                    int availableBalance = globalMap.getOrDefault(from, 0);
                    if(availableBalance < txn.value)
                        return false;
                    globalMap.put(from, globalMap.get(from)- txn.value);
                    globalMap.put(to, globalMap.get(to)+ txn.value);
                    availableBalanceMap.put(from, globalMap.getOrDefault(from, 0));
                    availableBalanceMap.put(to, globalMap.getOrDefault(to, 0));
                }

            }
            return true;
        }


    }
}
