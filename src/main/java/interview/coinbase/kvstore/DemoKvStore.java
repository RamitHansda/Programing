package interview.coinbase.kvstore;

public class DemoKvStore {
    public static void main(String[] args) {
        KVStore kvStore = new KVStore();
        kvStore.put("ramit", "rami", 10, 5L);
        kvStore.put("ramittt", "rami", 10, 5L);
        kvStore.put("ramit1212", "rami", 10, 5L);
        System.out.println(kvStore.get("ramit", 10));

        System.out.println(kvStore.prefixSearch("ramit", 12));

        int backupId = kvStore.createBackUp(12);
        System.out.println("backup is "+ backupId );
        kvStore.restore(backupId);
        System.out.println(kvStore.prefixSearch("ramit", 12));
    }
}
