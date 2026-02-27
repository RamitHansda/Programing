package lld.messagebroker;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Demo: Topic (pub/sub) and Queue (point-to-point) usage with the reusable message broker.
 */
public class MessageBrokerDemo {

    public static void main(String[] args) throws InterruptedException {
        MessageBroker broker = new InMemoryMessageBroker();

        System.out.println("=== Topic (Publish-Subscribe) ===");
        demoTopic(broker);

        System.out.println("\n=== Queue (Point-to-Point) ===");
        demoQueue(broker);

        broker.shutdown();
    }

    private static void demoTopic(MessageBroker broker) throws InterruptedException {
        Topic<String> events = broker.topic("events");

        Subscriber<String> sub1 = msg -> System.out.println("  [Sub1] " + msg.getPayload());
        Subscriber<String> sub2 = msg -> System.out.println("  [Sub2] " + msg.getPayload());

        events.subscribe(sub1);
        events.subscribe(sub2);

        events.publish("Hello");
        events.publish(new Message<>("World", Map.of("source", "demo")));

        events.unsubscribe(sub2);
        events.publish("Only Sub1 sees this");

        Thread.sleep(100);
    }

    private static void demoQueue(MessageBroker broker) throws InterruptedException {
        Queue<String> tasks = broker.queue("tasks");

        // Producer
        ExecutorService producers = Executors.newSingleThreadExecutor();
        producers.submit(() -> {
            try {
                for (int i = 1; i <= 3; i++) {
                    tasks.enqueue("task-" + i);
                    System.out.println("  Produced: task-" + i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Competing consumers
        ExecutorService consumers = Executors.newFixedThreadPool(2);
        for (int c = 0; c < 2; c++) {
            final int id = c + 1;
            consumers.submit(() -> {
                try {
                    while (true) {
                        Optional<Message<String>> msg = tasks.dequeue(2, TimeUnit.SECONDS);
                        if (msg.isEmpty()) break;
                        System.out.println("  Consumer " + id + " processed: " + msg.get().getPayload());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        producers.shutdown();
        producers.awaitTermination(5, TimeUnit.SECONDS);
        consumers.shutdown();
        consumers.awaitTermination(5, TimeUnit.SECONDS);
    }
}
