package lld.multiplayer;

import lld.multiplayer.event.SessionEvent;
import lld.multiplayer.event.SessionEventBus;
import lld.multiplayer.event.SessionEventType;
import lld.multiplayer.exception.DuplicateActionException;
import lld.multiplayer.exception.InvalidSessionStateException;
import lld.multiplayer.exception.PlayerNotInSessionException;
import lld.multiplayer.exception.SessionFullException;
import lld.multiplayer.exception.SessionNotFoundException;
import lld.multiplayer.model.GameAction;
import lld.multiplayer.model.Player;
import lld.multiplayer.model.PlayerState;
import lld.multiplayer.model.Session;
import lld.multiplayer.model.SessionConfig;
import lld.multiplayer.model.SessionState;
import lld.multiplayer.session.SessionManager;
import lld.multiplayer.session.SessionManagerImpl;
import lld.multiplayer.store.InMemorySessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    private InMemorySessionStore store;
    private SessionEventBus eventBus;
    private SessionManagerImpl manager;
    private List<SessionEvent> capturedEvents;

    private static final SessionConfig TWO_PLAYER_CONFIG =
            SessionConfig.builder("TEST_GAME")
                    .minPlayers(2).maxPlayers(2)
                    .reconnectGracePeriodSeconds(10)
                    .sessionTtlSeconds(300)
                    .build();

    @BeforeEach
    void setUp() {
        capturedEvents = new CopyOnWriteArrayList<>();
        store = new InMemorySessionStore();
        eventBus = new SessionEventBus();
        eventBus.subscribe(capturedEvents::add);
        manager = new SessionManagerImpl(store, eventBus);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        store.shutdown();
        eventBus.shutdown();
    }

    // -----------------------------------------------------------------------
    // createSession
    // -----------------------------------------------------------------------

    @Test
    void createSession_addsCreatorAndPublishesEvent() {
        Player alice = new Player("Alice");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);

        assertNotNull(session.getSessionId());
        assertEquals(SessionState.WAITING_FOR_PLAYERS, session.getState());
        assertEquals(1, session.getPlayerCount());
        assertEquals(PlayerState.IN_LOBBY, alice.getState());
        assertTrue(store.findById(session.getSessionId()).isPresent());
    }

    // -----------------------------------------------------------------------
    // joinSession
    // -----------------------------------------------------------------------

    @Test
    void joinSession_secondPlayerJoinsSuccessfully() {
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);

        Session updated = manager.joinSession(session.getSessionId(), bob);

        assertEquals(2, updated.getPlayerCount());
        assertEquals(PlayerState.IN_LOBBY, bob.getState());
    }

    @Test
    void joinSession_throwsWhenFull() {
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Player carol = new Player("Carol");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);
        manager.joinSession(session.getSessionId(), bob);

        assertThrows(SessionFullException.class,
                () -> manager.joinSession(session.getSessionId(), carol));
    }

    @Test
    void joinSession_throwsWhenSessionNotFound() {
        assertThrows(SessionNotFoundException.class,
                () -> manager.joinSession("no-such-id", new Player("X")));
    }

    @Test
    void joinSession_throwsWhenSessionNotInWaitingState() {
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Player carol = new Player("Carol");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);
        manager.joinSession(session.getSessionId(), bob);
        manager.startSession(session.getSessionId());

        assertThrows(InvalidSessionStateException.class,
                () -> manager.joinSession(session.getSessionId(), carol));
    }

    // -----------------------------------------------------------------------
    // startSession
    // -----------------------------------------------------------------------

    @Test
    void startSession_transitionsToActive() {
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);
        manager.joinSession(session.getSessionId(), bob);

        manager.startSession(session.getSessionId());

        assertEquals(SessionState.ACTIVE, session.getState());
        assertNotNull(session.getStartedAt());
        assertEquals(PlayerState.IN_SESSION, alice.getState());
        assertEquals(PlayerState.IN_SESSION, bob.getState());
    }

    @Test
    void startSession_throwsWhenNotEnoughPlayers() {
        Player alice = new Player("Alice");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);

        assertThrows(IllegalStateException.class,
                () -> manager.startSession(session.getSessionId()));
    }

    // -----------------------------------------------------------------------
    // leaveSession
    // -----------------------------------------------------------------------

    @Test
    void leaveSession_playerLeavesBeforeStart() {
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);
        manager.joinSession(session.getSessionId(), bob);

        manager.leaveSession(session.getSessionId(), bob);

        assertEquals(1, session.getPlayerCount());
        assertEquals(PlayerState.IDLE, bob.getState());
    }

    @Test
    void leaveSession_pausesSessionWhenBelowMin() {
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);
        manager.joinSession(session.getSessionId(), bob);
        manager.startSession(session.getSessionId());

        manager.leaveSession(session.getSessionId(), bob);

        assertEquals(SessionState.PAUSED, session.getState());
    }

    @Test
    void leaveSession_abandonsSessionWhenLastPlayerLeaves() throws InterruptedException {
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);
        manager.joinSession(session.getSessionId(), bob);
        manager.startSession(session.getSessionId());

        manager.leaveSession(session.getSessionId(), alice);
        manager.leaveSession(session.getSessionId(), bob);

        Thread.sleep(50); // let async events settle
        assertEquals(SessionState.ABANDONED, session.getState());
    }

    @Test
    void leaveSession_throwsWhenPlayerNotInSession() {
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);

        assertThrows(PlayerNotInSessionException.class,
                () -> manager.leaveSession(session.getSessionId(), bob));
    }

    // -----------------------------------------------------------------------
    // submitAction
    // -----------------------------------------------------------------------

    @Test
    void submitAction_acceptedInActiveSession() {
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);
        manager.joinSession(session.getSessionId(), bob);
        manager.startSession(session.getSessionId());

        GameAction action = GameAction.builder(alice.getPlayerId(), "MOVE")
                .payload("x", 1).payload("y", 2)
                .build();
        assertDoesNotThrow(() -> manager.submitAction(session.getSessionId(), action));
    }

    @Test
    void submitAction_rejectsDuplicateIdempotencyKey() {
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);
        manager.joinSession(session.getSessionId(), bob);
        manager.startSession(session.getSessionId());

        GameAction action = GameAction.builder(alice.getPlayerId(), "MOVE")
                .idempotencyKey("unique-key-1")
                .build();
        manager.submitAction(session.getSessionId(), action);

        assertThrows(DuplicateActionException.class,
                () -> manager.submitAction(session.getSessionId(), action));
    }

    @Test
    void submitAction_throwsWhenSessionNotActive() {
        Player alice = new Player("Alice");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);

        GameAction action = GameAction.builder(alice.getPlayerId(), "MOVE").build();
        assertThrows(InvalidSessionStateException.class,
                () -> manager.submitAction(session.getSessionId(), action));
    }

    // -----------------------------------------------------------------------
    // disconnect / reconnect
    // -----------------------------------------------------------------------

    @Test
    void disconnect_pausesActiveSession() {
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);
        manager.joinSession(session.getSessionId(), bob);
        manager.startSession(session.getSessionId());

        manager.notifyDisconnect(session.getSessionId(), bob.getPlayerId());

        assertEquals(SessionState.PAUSED, session.getState());
        assertEquals(PlayerState.DISCONNECTED, bob.getState());
    }

    @Test
    void reconnect_resumesSession() throws InterruptedException {
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);
        manager.joinSession(session.getSessionId(), bob);
        manager.startSession(session.getSessionId());
        manager.notifyDisconnect(session.getSessionId(), bob.getPlayerId());

        assertEquals(SessionState.PAUSED, session.getState());

        manager.notifyReconnect(session.getSessionId(), bob.getPlayerId());

        assertEquals(SessionState.ACTIVE, session.getState());
        assertEquals(PlayerState.IN_SESSION, bob.getState());
    }

    @Test
    void disconnect_abandonsSessionAfterGraceExpiry() throws InterruptedException {
        SessionConfig shortGrace = SessionConfig.builder("TEST_GAME")
                .minPlayers(2).maxPlayers(2)
                .reconnectGracePeriodSeconds(0) // immediate expiry
                .sessionTtlSeconds(300)
                .build();

        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Session session = manager.createSession(shortGrace, alice);
        manager.joinSession(session.getSessionId(), bob);
        manager.startSession(session.getSessionId());
        manager.notifyDisconnect(session.getSessionId(), bob.getPlayerId());

        // With 0-second grace, abandonment fires synchronously in the timer executor;
        // give it a brief moment to complete
        Thread.sleep(100);

        assertEquals(SessionState.ABANDONED, session.getState());
    }

    // -----------------------------------------------------------------------
    // endSession
    // -----------------------------------------------------------------------

    @Test
    void endSession_transitionsToFinished() {
        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);
        manager.joinSession(session.getSessionId(), bob);
        manager.startSession(session.getSessionId());

        manager.endSession(session.getSessionId());

        assertEquals(SessionState.FINISHED, session.getState());
        assertEquals(PlayerState.IDLE, alice.getState());
        assertEquals(PlayerState.IDLE, bob.getState());
    }

    @Test
    void endSession_throwsWhenNotActiveOrPaused() {
        Player alice = new Player("Alice");
        Session session = manager.createSession(TWO_PLAYER_CONFIG, alice);

        assertThrows(InvalidSessionStateException.class,
                () -> manager.endSession(session.getSessionId()));
    }

    // -----------------------------------------------------------------------
    // getSession / getAllSessions
    // -----------------------------------------------------------------------

    @Test
    void getSession_returnsEmptyForUnknownId() {
        Optional<Session> result = manager.getSession("does-not-exist");
        assertTrue(result.isEmpty());
    }

    @Test
    void getAllSessions_includesCreatedSessions() {
        Player a = new Player("A");
        Player b = new Player("B");
        manager.createSession(TWO_PLAYER_CONFIG, a);
        manager.createSession(TWO_PLAYER_CONFIG, b);

        assertTrue(manager.getAllSessions().size() >= 2);
    }
}
