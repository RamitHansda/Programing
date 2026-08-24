package lld.inmempubsub;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class InMemoryPubSub {

    // -----------------------------
    // Message
    // -----------------------------

    public record Message(
            String topic,
            String payload
    ) {
    }


    // -----------------------------
    // Subscriber
    // -----------------------------

    @FunctionalInterface
    public interface Subscriber {
        void onMessage(Message message);
    }


    // -----------------------------
    // PubSub Broker
    // -----------------------------

    public static class PubSubBroker implements AutoCloseable {

        private final Map<String, Set<Subscriber>> topicSubscribers =
                new ConcurrentHashMap<>();

        private final ExecutorService executor;

        public PubSubBroker(int numberOfWorkers) {
            this.executor = Executors.newFixedThreadPool(numberOfWorkers);
        }


        // -----------------------------
        // Subscribe
        // -----------------------------

        public void subscribe(
                String topic,
                Subscriber subscriber
        ) {
            Objects.requireNonNull(topic);
            Objects.requireNonNull(subscriber);

            topicSubscribers
                    .computeIfAbsent(
                            topic,
                            ignored -> new HashSet<>()
                    )
                    .add(subscriber);
        }


        // -----------------------------
        // Unsubscribe
        // -----------------------------

        public void unsubscribe(
                String topic,
                Subscriber subscriber
        ) {
            Objects.requireNonNull(topic);
            Objects.requireNonNull(subscriber);

            Set<Subscriber> subscribers =
                    topicSubscribers.get(topic);

            if (subscribers == null) {
                return;
            }

            subscribers.remove(subscriber);

            // Optional cleanup
            if (subscribers.isEmpty()) {
                topicSubscribers.remove(topic, subscribers);
            }
        }


        // -----------------------------
        // Publish
        // -----------------------------

        public void publish(
                String topic,
                String payload
        ) {
            Objects.requireNonNull(topic);

            Message message =
                    new Message(topic, payload);

            Set<Subscriber> subscribers =
                    topicSubscribers.get(topic);

            if (subscribers == null) {
                return;
            }

            /*
             * Don't execute subscriber code while holding
             * any broker lock.
             */
            for (Subscriber subscriber : subscribers) {

                executor.submit(() -> {
                    try {
                        subscriber.onMessage(message);
                    } catch (Exception e) {
                        // Don't let one subscriber
                        // affect other subscribers.
                        System.err.println(
                                "Subscriber failed: "
                                        + e.getMessage()
                        );
                    }
                });
            }
        }


        // -----------------------------
        // Shutdown
        // -----------------------------

        @Override
        public void close() {
            executor.shutdown();
        }
    }


    // -----------------------------
    // Example
    // -----------------------------

    public static void main(String[] args)
            throws InterruptedException {

        try (PubSubBroker broker =
                     new PubSubBroker(4)) {

            Subscriber subscriber1 =
                    message -> System.out.println(
                            "Subscriber 1: "
                                    + message.payload()
                    );

            Subscriber subscriber2 =
                    message -> System.out.println(
                            "Subscriber 2: "
                                    + message.payload()
                    );


            broker.subscribe(
                    "payments",
                    subscriber1
            );

            broker.subscribe(
                    "payments",
                    subscriber2
            );


            broker.publish(
                    "payments",
                    "Payment successful"
            );

            Thread.sleep(1000);

            broker.unsubscribe(
                    "payments",
                    subscriber1
            );

            broker.publish(
                    "payments",
                    "Payment failed"
            );

            Thread.sleep(1000);
        }
    }
}
