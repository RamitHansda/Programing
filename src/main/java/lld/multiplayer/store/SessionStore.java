package lld.multiplayer.store;

import lld.multiplayer.model.Session;

import java.util.Collection;
import java.util.Optional;

/**
 * Repository abstraction for {@link Session} persistence.
 *
 * <p>The interface is intentionally minimal. {@link InMemorySessionStore} provides the
 * in-process implementation used by this module. A production deployment would swap in a
 * Redis- or database-backed store without changing any code that depends on this interface.
 */
public interface SessionStore {

    /**
     * Persists or replaces the given session.
     *
     * @param session the session to store; must not be {@code null}
     */
    void save(Session session);

    /**
     * Retrieves a session by its ID.
     *
     * @param sessionId the identifier to look up
     * @return an {@link Optional} containing the session, or empty if not found or expired
     */
    Optional<Session> findById(String sessionId);

    /**
     * Returns all currently stored (non-expired) sessions.
     */
    Collection<Session> findAll();

    /**
     * Removes the session with the given ID from the store.
     *
     * @return {@code true} if the session existed and was removed
     */
    boolean remove(String sessionId);

    /**
     * Evicts all sessions whose last-activity timestamp is older than their configured TTL.
     * Implementations may call this lazily on read, on a background timer, or both.
     */
    void evictExpired();

    /** Returns the number of sessions currently in the store. */
    int size();
}
