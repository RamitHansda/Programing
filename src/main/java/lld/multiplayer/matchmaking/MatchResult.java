package lld.multiplayer.matchmaking;

import lld.multiplayer.model.Player;
import lld.multiplayer.model.Session;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of a successful matchmaking attempt.
 *
 * <p>Contains the newly created {@link Session} and the list of {@link Player}s that were
 * grouped together. Consumers (e.g. the client gateway) use this to notify each player of
 * their session ID so they can connect.
 */
public final class MatchResult {

    private final Session session;
    private final List<Player> matchedPlayers;

    public MatchResult(Session session, List<Player> matchedPlayers) {
        this.session = Objects.requireNonNull(session);
        this.matchedPlayers = List.copyOf(matchedPlayers);
    }

    public Session getSession() { return session; }
    public List<Player> getMatchedPlayers() { return matchedPlayers; }

    @Override
    public String toString() {
        return "MatchResult{sessionId='" + session.getSessionId()
                + "', players=" + matchedPlayers.size() + "}";
    }
}
