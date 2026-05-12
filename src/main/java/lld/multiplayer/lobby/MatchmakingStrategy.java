package lld.multiplayer.lobby;

import lld.multiplayer.model.Player;
import lld.multiplayer.model.SessionConfig;

import java.util.List;
import java.util.Optional;

/**
 * Strategy for deciding which subset of waiting players should form a session.
 *
 * <p>Implementations receive the current snapshot of players in the matchmaking queue and
 * return a matched group if one can be formed according to their criteria, or
 * {@link Optional#empty()} if no valid match exists yet.
 *
 * <p>Strategies are intentionally stateless; state is owned by the queue outside.
 */
public interface MatchmakingStrategy {

    /**
     * Attempts to select a group of players that should be placed into the same session.
     *
     * @param availablePlayers current snapshot of all players waiting for a match;
     *                         the list is ordered oldest-wait-time first
     * @param config           the {@link SessionConfig} the resulting session will use
     * @return a non-empty list of players to group together, or {@link Optional#empty()} if
     *         no group can be formed from the current pool
     */
    Optional<List<Player>> match(List<Player> availablePlayers, SessionConfig config);
}
