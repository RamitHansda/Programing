package lld.messagebroker;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable message envelope: payload plus optional headers and id.
 * Reusable across topics and queues.
 *
 * @param <T> type of the payload
 */
public final class Message<T> {

    private final String id;
    private final T payload;
    private final Map<String, String> headers;

    public Message(T payload) {
        this(UUID.randomUUID().toString(), payload, Collections.emptyMap());
    }

    public Message(T payload, Map<String, String> headers) {
        this(UUID.randomUUID().toString(), payload, headers == null ? Collections.emptyMap() : Map.copyOf(headers));
    }

    public Message(String id, T payload, Map<String, String> headers) {
        this.id = Objects.requireNonNull(id, "id");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.headers = headers == null ? Collections.emptyMap() : Map.copyOf(headers);
    }

    public String getId() {
        return id;
    }

    public T getPayload() {
        return payload;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getHeader(String key) {
        return headers.get(key);
    }

    @Override
    public String toString() {
        return "Message{id='" + id + "', payload=" + payload + "}";
    }
}
