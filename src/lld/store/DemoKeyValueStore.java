package lld.store;

public class DemoKeyValueStore {
    public static void main(String[] args) {
        try (InMemoryKeyValueStore<String> store = new InMemoryKeyValueStore<>()) {
            store.put("user:1:name", "Alice");
            store.put("user:1:session", "s123", java.time.Duration.ofMillis(300));
            store.put("user:2:name", "Bob");

            System.out.println("all user: keys -> " + store.keysWithPrefix("user:"));
            System.out.println("get user:1:name -> " + store.get("user:1:name").orElse("<missing>"));

            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println("after TTL, get user:1:session -> " + store.get("user:1:session").orElse("<expired>"));
            System.out.println("keys with prefix user:1 -> " + store.keysWithPrefix("user:1"));
            System.out.println("size -> " + store.size());
        }
    }
}
