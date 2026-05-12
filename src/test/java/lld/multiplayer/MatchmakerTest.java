package lld.multiplayer;

import lld.multiplayer.event.SessionEventBus;
import lld.multiplayer.lobby.FirstAvailableStrategy;
import lld.multiplayer.matchmaking.MatchResult;
import lld.multiplayer.matchmaking.Matchmaker;
import lld.multiplayer.model.Player;
import lld.multiplayer.model.PlayerState;
import lld.multiplayer.model.Session;
import lld.multiplayer.model.SessionConfig;
import lld.multiplayer.model.SessionState;
import lld.multiplayer.session.SessionManagerImpl;
import lld.multiplayer.store.InMemorySessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakerTest {

    private InMemorySessionStore store;
    private SessionEventBus eventBus;
    private SessionManagerImpl sessionManager;
    private Matchmaker matchmaker;

    private static final SessionConfig CONFIG = SessionConfig.builder("GAME")
            .minPlayers(2).maxPlayers(2)
            .sessionTtlSeconds(300)
            .build();

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
        eventBus = new SessionEventBus();
        sessionManager = new SessionManagerImpl(store, eventBus);
    }

    @AfterEach
    void tearDown() {
        if (matchmaker != null) matchmaker.shutdown();
        sessionManager.shutdown();
        store.shutdown();
        eventBus.shutdown();
    }

    @Test
    void enqueue_setsPlayerStateToInMatchmaking() {
        matchmaker = new Matchmaker(sessionManager, new FirstAvailableStrategy(), null, 10_000);

        Player p = new Player("P");
        matchmaker.enqueue(p, CONFIG);

        assertEquals(PlayerState.IN_MATCHMAKING, p.getState());
        assertEquals(1, matchmaker.getQueueSize(CONFIG.getGameType()));
    }

    @Test
    void dequeue_resetsStateToIdle() {
        matchmaker = new Matchmaker(sessionManager, new FirstAvailableStrategy(), null, 10_000);

        Player p = new Player("P");
        matchmaker.enqueue(p, CONFIG);
        matchmaker.dequeue(p);

        assertEquals(PlayerState.IDLE, p.getState());
        assertEquals(0, matchmaker.getQueueSize(CONFIG.getGameType()));
    }

    @Test
    void enqueue_throwsWhenPlayerNotIdle() {
        matchmaker = new Matchmaker(sessionManager, new FirstAvailableStrategy(), null, 10_000);

        Player p = new Player("P");
        p.setState(PlayerState.IN_SESSION);

        assertThrows(IllegalStateException.class, () -> matchmaker.enqueue(p, CONFIG));
    }

    @Test
    void matchingCycle_pairsPlayersAndCreatesSession() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<MatchResult> resultRef = new AtomicReference<>();

        matchmaker = new Matchmaker(sessionManager, new FirstAvailableStrategy(),
                result -> { resultRef.set(result); latch.countDown(); }, 50);

        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");

        matchmaker.enqueue(alice, CONFIG);
        matchmaker.enqueue(bob,   CONFIG);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Match should fire within 2s");

        MatchResult result = resultRef.get();
        assertNotNull(result);
        assertEquals(2, result.getMatchedPlayers().size());
        assertTrue(result.getMatchedPlayers().contains(alice));
        assertTrue(result.getMatchedPlayers().contains(bob));

        Session session = result.getSession();
        assertEquals(SessionState.WAITING_FOR_PLAYERS, session.getState());
        assertEquals(2, session.getPlayerCount());
        assertEquals(0, matchmaker.getQueueSize(CONFIG.getGameType()));
    }

    @Test
    void matchingCycle_doesNotMatchWithSinglePlayer() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        matchmaker = new Matchmaker(sessionManager, new FirstAvailableStrategy(),
                r -> latch.countDown(), 50);

        Player solo = new Player("Solo");
        matchmaker.enqueue(solo, CONFIG);

        boolean fired = latch.await(400, TimeUnit.MILLISECONDS);
        assertFalse(fired, "Match should not fire with only 1 player");
        assertEquals(1, matchmaker.getQueueSize(CONFIG.getGameType()));
    }

    @Test
    void matchingCycle_prunesDisconnectedPlayers() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        matchmaker = new Matchmaker(sessionManager, new FirstAvailableStrategy(),
                r -> latch.countDown(), 50);

        Player gone  = new Player("Gone");
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");

        matchmaker.enqueue(gone, CONFIG);
        gone.setState(PlayerState.DISCONNECTED); // simulate drop while in queue

        matchmaker.enqueue(alice, CONFIG);
        matchmaker.enqueue(bob,   CONFIG);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Remaining two players should be matched");
    }

    @Test
    void matchingCycle_runManuallyWithoutBackgroundThread() {
        matchmaker = new Matchmaker(sessionManager, new FirstAvailableStrategy(), null, Long.MAX_VALUE);

        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        matchmaker.enqueue(alice, CONFIG);
        matchmaker.enqueue(bob,   CONFIG);

        matchmaker.runMatchingCycle();

        assertEquals(0, matchmaker.getQueueSize(CONFIG.getGameType()));
        assertEquals(1, store.size());
    }
}
