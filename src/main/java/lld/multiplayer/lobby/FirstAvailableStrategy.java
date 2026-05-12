package lld.multiplayer.lobby;

import lld.multiplayer.model.Player;
import lld.multiplayer.model.SessionConfig;

import java.util.List;
import java.util.Optional;

/**
 * Simplest possible {@link MatchmakingStrategy}: take the first {@code minPlayers} waiting
 * players (FIFO order) without any skill, region, or latency consideration.
 *
 * <p>This is appropriate for prototypes, casual games, or as a fallback when other criteria
 * cannot be met within a timeout. For competitive games, swap in a skill-bracket or
 * region-aware strategy.
 */
public class FirstAvailableStrategy implements MatchmakingStrategy {

    @Override
    public Optional<List<Player>> match(List<Player> availablePlayers, SessionConfig config) {
        int required = config.getMinPlayers();
        if (availablePlayers.size() < required) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(availablePlayers.subList(0, required)));
    }
}
