package lld.designpatterns.memento;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration manager. Memento captures snapshot for restore (rollback) without exposing internals.
 */
public final class ConfigurationManager {

    private final Map<String, String> config = new HashMap<>();

    public void set(String key, String value) {
        config.put(Objects.requireNonNull(key), value);
    }

    public String get(String key) {
        return config.get(key);
    }

    /**
     * Saves current state to a memento. Caller cannot mutate internal state via the memento.
     */
    public ConfigMemento saveSnapshot(String name) {
        return new ConfigMemento(name, new HashMap<>(config));
    }

    /**
     * Restores state from a memento previously created by this manager.
     */
    public void restore(ConfigMemento memento) {
        if (memento == null) return;
        config.clear();
        config.putAll(memento.getStateSnapshot());
    }

    /**
     * Memento: opaque snapshot. Only ConfigurationManager can read/write state.
     */
    public static final class ConfigMemento {
        private final String name;
        private final Map<String, String> state;

        ConfigMemento(String name, Map<String, String> state) {
            this.name = name;
            this.state = new HashMap<>(state);
        }

        Map<String, String> getStateSnapshot() {
            return new HashMap<>(state);
        }

        public String getName() { return name; }
    }
}
