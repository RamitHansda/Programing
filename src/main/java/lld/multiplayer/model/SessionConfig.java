package lld.multiplayer.model;

import java.util.Objects;

/**
 * Immutable configuration for a multiplayer session.
 *
 * <p>Use {@link Builder} to construct instances. Sensible defaults are provided so callers only
 * need to specify the values that differ from the defaults.
 */
public final class SessionConfig {

    /** Logical game type identifier (e.g. "TICTACTOE", "CHESS"). Free-form string. */
    private final String gameType;

    /** Minimum number of players required before the session can start. */
    private final int minPlayers;

    /** Maximum number of players allowed in the session simultaneously. */
    private final int maxPlayers;

    /** Seconds of inactivity after which an idle session is evicted. */
    private final long sessionTtlSeconds;

    /**
     * Seconds a disconnected player has to reconnect before the system marks them as
     * permanently gone and potentially abandons the session.
     */
    private final long reconnectGracePeriodSeconds;

    /** Whether spectators (non-playing observers) are permitted. */
    private final boolean spectatorsAllowed;

    /** Maximum number of spectators when {@code spectatorsAllowed} is true. */
    private final int maxSpectators;

    private SessionConfig(Builder b) {
        this.gameType = b.gameType;
        this.minPlayers = b.minPlayers;
        this.maxPlayers = b.maxPlayers;
        this.sessionTtlSeconds = b.sessionTtlSeconds;
        this.reconnectGracePeriodSeconds = b.reconnectGracePeriodSeconds;
        this.spectatorsAllowed = b.spectatorsAllowed;
        this.maxSpectators = b.maxSpectators;
    }

    public String getGameType() { return gameType; }
    public int getMinPlayers() { return minPlayers; }
    public int getMaxPlayers() { return maxPlayers; }
    public long getSessionTtlSeconds() { return sessionTtlSeconds; }
    public long getReconnectGracePeriodSeconds() { return reconnectGracePeriodSeconds; }
    public boolean isSpectatorsAllowed() { return spectatorsAllowed; }
    public int getMaxSpectators() { return maxSpectators; }

    @Override
    public String toString() {
        return "SessionConfig{gameType='" + gameType + "', players=" + minPlayers + "-"
                + maxPlayers + ", ttl=" + sessionTtlSeconds + "s, reconnectGrace="
                + reconnectGracePeriodSeconds + "s}";
    }

    public static Builder builder(String gameType) {
        return new Builder(gameType);
    }

    public static final class Builder {
        private final String gameType;
        private int minPlayers = 2;
        private int maxPlayers = 2;
        private long sessionTtlSeconds = 3600;
        private long reconnectGracePeriodSeconds = 30;
        private boolean spectatorsAllowed = false;
        private int maxSpectators = 0;

        private Builder(String gameType) {
            this.gameType = Objects.requireNonNull(gameType, "gameType must not be null");
        }

        public Builder minPlayers(int min) { this.minPlayers = min; return this; }
        public Builder maxPlayers(int max) { this.maxPlayers = max; return this; }
        public Builder sessionTtlSeconds(long ttl) { this.sessionTtlSeconds = ttl; return this; }
        public Builder reconnectGracePeriodSeconds(long grace) { this.reconnectGracePeriodSeconds = grace; return this; }
        public Builder spectatorsAllowed(boolean allowed) { this.spectatorsAllowed = allowed; return this; }
        public Builder maxSpectators(int max) { this.maxSpectators = max; return this; }

        public SessionConfig build() {
            if (minPlayers < 1) throw new IllegalArgumentException("minPlayers must be >= 1");
            if (maxPlayers < minPlayers) throw new IllegalArgumentException("maxPlayers must be >= minPlayers");
            if (sessionTtlSeconds <= 0) throw new IllegalArgumentException("sessionTtlSeconds must be > 0");
            if (reconnectGracePeriodSeconds < 0) throw new IllegalArgumentException("reconnectGracePeriodSeconds must be >= 0");
            return new SessionConfig(this);
        }
    }
}
