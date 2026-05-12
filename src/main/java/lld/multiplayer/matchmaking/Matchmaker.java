package lld.multiplayer.matchmaking;

import lld.multiplayer.lobby.MatchmakingStrategy;
import lld.multiplayer.model.Player;
import lld.multiplayer.model.PlayerState;
import lld.multiplayer.model.Session;
import lld.multiplayer.model.SessionConfig;
import lld.multiplayer.session.SessionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Manages a per-{@code gameType} matchmaking queue and runs a periodic matching loop.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Each game type gets its own {@link CopyOnWriteArrayList} of {@link MatchmakingEntry}s.
 *       Using CoW lists keeps snapshot iteration cheap even though writes (enqueue/dequeue)
 *       happen infrequently relative to reads.</li>
 *   <li>A single {@link ScheduledExecutorService} runs {@link #runMatchingCycle()} every
 *       {@code tickIntervalMs} milliseconds. The cycle iterates all game-type queues and, for
 *       each, tries to form a group using the injected {@link MatchmakingStrategy}.</li>
 *   <li>When a match is found the players are dequeued, a session is created via
 *       {@link SessionManager#createSession}, and the optional {@code onMatch} callback is
 *       invoked so the gateway layer can notify clients.</li>
 *   <li>Players that enter an inconsistent state (e.g. disconnected while waiting) are pruned
 *       on each cycle.</li>
 * </ul>
 *
 * <p>Call {@link #shutdown()} to stop the background thread cleanly.
 */
public class Matchmaker {

    private static final Logger LOG = Logger.getLogger(Matchmaker.class.getName());

    private final SessionManager sessionManager;
    private final MatchmakingStrategy strategy;
    private final Consumer<MatchResult> onMatch;

    /** key = gameType */
    private final Map<String, List<MatchmakingEntry>> queues = new ConcurrentHashMap<>();

    /** key = playerId — for O(1) cancel-by-player-id */
    private final Map<String, MatchmakingEntry> entriesByPlayer = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickFuture;

    public Matchmaker(SessionManager sessionManager, MatchmakingStrategy strategy) {
        this(sessionManager, strategy, null, 500);
    }

    /**
     * @param sessionManager  used to create sessions when a match is found
     * @param strategy        decides which players to group together
     * @param onMatch         optional callback invoked on the ticker thread after each match
     * @param tickIntervalMs  how often to run the matching cycle (milliseconds)
     */
    public Matchmaker(SessionManager sessionManager,
                      MatchmakingStrategy strategy,
                      Consumer<MatchResult> onMatch,
                      long tickIntervalMs) {
        this.sessionManager = sessionManager;
        this.strategy = strategy;
        this.onMatch = onMatch;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "matchmaker-tick");
            t.setDaemon(true);
            return t;
        });
        this.tickFuture = scheduler.scheduleAtFixedRate(
                this::runMatchingCycle, tickIntervalMs, tickIntervalMs, TimeUnit.MILLISECONDS);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Enqueues {@code player} for matchmaking using the given session configuration.
     * If the player is already in the queue their previous entry is replaced.
     *
     * @throws IllegalStateException if the player is not in the {@link PlayerState#IDLE} state
     */
    public void enqueue(Player player, SessionConfig config) {
        if (player.getState() != PlayerState.IDLE) {
            throw new IllegalStateException(
                    "Player " + player.getPlayerId() + " is not IDLE (state=" + player.getState() + ")");
        }
        dequeue(player);

        MatchmakingEntry entry = new MatchmakingEntry(player, config);
        queues.computeIfAbsent(config.getGameType(), k -> new CopyOnWriteArrayList<>()).add(entry);
        entriesByPlayer.put(player.getPlayerId(), entry);
        player.setState(PlayerState.IN_MATCHMAKING);
        LOG.fine(() -> "Enqueued " + player.getPlayerId() + " for " + config.getGameType());
    }

    /**
     * Removes {@code player} from the matchmaking queue. No-op if they are not queued.
     */
    public void dequeue(Player player) {
        MatchmakingEntry entry = entriesByPlayer.remove(player.getPlayerId());
        if (entry == null) return;

        List<MatchmakingEntry> queue = queues.get(entry.getDesiredConfig().getGameType());
        if (queue != null) queue.remove(entry);

        if (player.getState() == PlayerState.IN_MATCHMAKING) {
            player.setState(PlayerState.IDLE);
        }
        LOG.fine(() -> "Dequeued " + player.getPlayerId());
    }

    /**
     * Returns a snapshot of players currently waiting for the given game type.
     */
    public List<Player> getWaitingPlayers(String gameType) {
        List<MatchmakingEntry> queue = queues.get(gameType);
        if (queue == null) return List.of();
        return queue.stream().map(MatchmakingEntry::getPlayer).collect(Collectors.toList());
    }

    public int getQueueSize(String gameType) {
        List<MatchmakingEntry> queue = queues.get(gameType);
        return queue == null ? 0 : queue.size();
    }

    // -----------------------------------------------------------------------
    // Matching loop
    // -----------------------------------------------------------------------

    public void runMatchingCycle() {
        for (Map.Entry<String, List<MatchmakingEntry>> queueEntry : queues.entrySet()) {
            String gameType = queueEntry.getKey();
            List<MatchmakingEntry> queue = queueEntry.getValue();

            pruneDisconnected(queue);

            if (queue.isEmpty()) continue;

            SessionConfig config = queue.get(0).getDesiredConfig();
            List<Player> candidates = queue.stream()
                    .map(MatchmakingEntry::getPlayer)
                    .collect(Collectors.toList());

            Optional<List<Player>> matched = strategy.match(candidates, config);
            if (matched.isEmpty()) continue;

            List<Player> group = matched.get();
            List<MatchmakingEntry> toRemove = new ArrayList<>();

            for (MatchmakingEntry e : queue) {
                if (group.contains(e.getPlayer())) {
                    toRemove.add(e);
                    entriesByPlayer.remove(e.getPlayer().getPlayerId());
                }
            }
            queue.removeAll(toRemove);

            try {
                Player host = group.get(0);
                host.setState(PlayerState.IDLE);
                Session session = sessionManager.createSession(config, host);

                for (int i = 1; i < group.size(); i++) {
                    Player p = group.get(i);
                    p.setState(PlayerState.IDLE);
                    sessionManager.joinSession(session.getSessionId(), p);
                }

                MatchResult result = new MatchResult(session, group);
                LOG.fine(() -> "Match formed: " + result);

                if (onMatch != null) {
                    try { onMatch.accept(result); }
                    catch (Exception e) { LOG.warning("onMatch callback threw: " + e.getMessage()); }
                }
            } catch (Exception e) {
                LOG.warning("Failed to create session for match in " + gameType + ": " + e.getMessage());
                group.forEach(p -> p.setState(PlayerState.IDLE));
            }
        }
    }

    private void pruneDisconnected(List<MatchmakingEntry> queue) {
        queue.removeIf(e -> {
            PlayerState s = e.getPlayer().getState();
            boolean stale = s == PlayerState.DISCONNECTED || s == PlayerState.IN_SESSION || s == PlayerState.IN_LOBBY;
            if (stale) entriesByPlayer.remove(e.getPlayer().getPlayerId());
            return stale;
        });
    }

    /** Stops the background tick thread. */
    public void shutdown() {
        if (tickFuture != null) tickFuture.cancel(false);
        scheduler.shutdown();
    }
}
