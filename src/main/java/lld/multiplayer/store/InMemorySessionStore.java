package lld.multiplayer.store;

import lld.multiplayer.model.Session;
import lld.multiplayer.model.SessionState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Thread-safe, in-memory {@link SessionStore} with TTL-based background eviction.
 *
 * <p>Implementation details:
 * <ul>
 *   <li>Sessions are stored in a {@link ConcurrentHashMap} keyed by session ID.</li>
 *   <li>A single-threaded {@link ScheduledExecutorService} runs {@link #evictExpired()} every
 *       {@code evictionIntervalSeconds} seconds so callers never experience latency spikes
 *       from synchronous eviction.</li>
 *   <li>Terminal sessions ({@code FINISHED}/{@code ABANDONED}) are treated as expired
 *       immediately regardless of the TTL setting.</li>
 *   <li>An optional {@code onEvict} callback lets the session manager react to evictions
 *       (e.g. to publish a {@code SESSION_ABANDONED} event) without coupling the store to
 *       the manager.</li>
 * </ul>
 *
 * <p>Call {@link #shutdown()} when the store is no longer needed to stop the background thread.
 */
public class InMemorySessionStore implements SessionStore {

    private static final Logger LOG = Logger.getLogger(InMemorySessionStore.class.getName());

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Consumer<Session> onEvict;
    private final ScheduledExecutorService evictionScheduler;

    public InMemorySessionStore() {
        this(null, 60);
    }

    /**
     * @param onEvict                  optional callback invoked for each evicted session
     * @param evictionIntervalSeconds  how often to run the eviction sweep (seconds)
     */
    public InMemorySessionStore(Consumer<Session> onEvict, long evictionIntervalSeconds) {
        this.onEvict = onEvict;
        this.evictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-store-evictor");
            t.setDaemon(true);
            return t;
        });
        evictionScheduler.scheduleAtFixedRate(
                this::evictExpired, evictionIntervalSeconds, evictionIntervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void save(Session session) {
        sessions.put(session.getSessionId(), session);
    }

    @Override
    public Optional<Session> findById(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) return Optional.empty();
        if (isExpired(session)) {
            sessions.remove(sessionId);
            notifyEvict(session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    @Override
    public Collection<Session> findAll() {
        evictExpired();
        return Collections.unmodifiableCollection(new ArrayList<>(sessions.values()));
    }

    @Override
    public boolean remove(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    @Override
    public void evictExpired() {
        List<Session> toEvict = new ArrayList<>();
        sessions.values().forEach(s -> {
            if (isExpired(s)) toEvict.add(s);
        });
        for (Session s : toEvict) {
            if (sessions.remove(s.getSessionId()) != null) {
                LOG.fine(() -> "Evicted session " + s.getSessionId());
                notifyEvict(s);
            }
        }
    }

    @Override
    public int size() {
        return sessions.size();
    }

    /** Stops the background eviction thread. */
    public void shutdown() {
        evictionScheduler.shutdown();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private boolean isExpired(Session session) {
        SessionState state = session.getState();
        if (state == SessionState.FINISHED || state == SessionState.ABANDONED) return true;

        long ttlMillis = session.getConfig().getSessionTtlSeconds() * 1000L;
        return Instant.now().isAfter(session.getLastActivityAt().plusMillis(ttlMillis));
    }

    private void notifyEvict(Session session) {
        if (onEvict != null) {
            try { onEvict.accept(session); }
            catch (Exception e) { LOG.warning("onEvict callback threw: " + e.getMessage()); }
        }
    }
}
