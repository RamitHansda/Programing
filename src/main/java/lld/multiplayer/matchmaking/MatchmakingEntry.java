package lld.multiplayer.matchmaking;

import lld.multiplayer.model.Player;
import lld.multiplayer.model.SessionConfig;

import java.time.Instant;
import java.util.Objects;

/**
 * An entry in the matchmaking queue representing a single player waiting for a match.
 *
 * <p>Carries the player reference, the session configuration they are willing to play with, and
 * the time they entered the queue (used for FIFO ordering and wait-time metrics).
 */
public final class MatchmakingEntry {

    private final Player player;
    private final SessionConfig desiredConfig;
    private final Instant enqueuedAt;

    public MatchmakingEntry(Player player, SessionConfig desiredConfig) {
        this.player = Objects.requireNonNull(player, "player must not be null");
        this.desiredConfig = Objects.requireNonNull(desiredConfig, "desiredConfig must not be null");
        this.enqueuedAt = Instant.now();
    }

    public Player getPlayer() { return player; }
    public SessionConfig getDesiredConfig() { return desiredConfig; }
    public Instant getEnqueuedAt() { return enqueuedAt; }

    @Override
    public String toString() {
        return "MatchmakingEntry{player=" + player.getPlayerId()
                + ", gameType='" + desiredConfig.getGameType() + "'}";
    }
}
