package lld.multiplayer;

import lld.multiplayer.event.SessionEventType;
import lld.multiplayer.lobby.Lobby;
import lld.multiplayer.matchmaking.MatchResult;
import lld.multiplayer.model.GameAction;
import lld.multiplayer.model.Player;
import lld.multiplayer.model.PlayerState;
import lld.multiplayer.model.Session;
import lld.multiplayer.model.SessionConfig;
import lld.multiplayer.model.SessionState;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end demonstration of the multiplayer session system.
 *
 * <p>Scenario A — Private lobby: two players create a room, play a move, one disconnects, the
 *   other waits for reconnection, then the game ends normally.
 *
 * <p>Scenario B — Matchmaking: two players independently enter the matchmaking queue; the
 *   background ticker pairs them into a session automatically.
 */
public class MultiplayerDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Multiplayer Session System Demo ===\n");

        scenarioA_privateLobby();
        System.out.println();
        scenarioB_matchmaking();

        System.out.println("\n=== Demo complete ===");
    }

    // -----------------------------------------------------------------------
    // Scenario A: Private Lobby → Start → Move → Disconnect → Reconnect → End
    // -----------------------------------------------------------------------

    private static void scenarioA_privateLobby() throws InterruptedException {
        System.out.println("--- Scenario A: Private Lobby ---");

        MultiplayerSessionSystem system = new MultiplayerSessionSystem();

        system.subscribe(event -> System.out.println("[EVENT] " + event.getType()
                + " session=" + event.getSessionId().substring(0, 8)
                + " payload=" + event.getPayload()));

        SessionConfig config = SessionConfig.builder("TICTACTOE")
                .minPlayers(2).maxPlayers(2)
                .reconnectGracePeriodSeconds(5)
                .sessionTtlSeconds(300)
                .build();

        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");

        Lobby lobby = system.createLobby(alice, config);
        System.out.println("Alice created lobby: " + lobby.getLobbyId().substring(0, 8));

        boolean joined = system.joinLobby(lobby.getLobbyId(), bob);
        System.out.println("Bob joined lobby: " + joined);

        MatchResult result = system.startLobbySession(lobby.getLobbyId());
        String sessionId = result.getSession().getSessionId();
        System.out.println("Session started: " + sessionId.substring(0, 8));

        Session session = system.getSession(sessionId).orElseThrow();
        assert session.getState() == SessionState.ACTIVE : "Expected ACTIVE, got " + session.getState();
        assert alice.getState() == PlayerState.IN_SESSION;
        assert bob.getState() == PlayerState.IN_SESSION;

        // Alice makes a move
        GameAction move = GameAction.builder(alice.getPlayerId(), "PLACE")
                .payload("row", 0).payload("col", 0)
                .idempotencyKey("alice-move-1")
                .build();
        system.submitAction(sessionId, move);
        System.out.println("Alice submitted move");

        // Idempotent retry must be rejected
        try {
            system.submitAction(sessionId, move);
            System.out.println("ERROR: duplicate action should have been rejected");
        } catch (lld.multiplayer.exception.DuplicateActionException e) {
            System.out.println("Duplicate action correctly rejected");
        }

        // Bob disconnects
        long graceExpiry = system.notifyDisconnect(sessionId, bob.getPlayerId());
        System.out.println("Bob disconnected; grace expires at epoch=" + graceExpiry);
        assert session.getState() == SessionState.PAUSED : session.getState();
        assert bob.getState() == PlayerState.DISCONNECTED;

        // Bob reconnects within the grace window
        Thread.sleep(200);
        system.notifyReconnect(sessionId, bob.getPlayerId());
        System.out.println("Bob reconnected");
        assert session.getState() == SessionState.ACTIVE
                : "Expected ACTIVE after reconnect, got " + session.getState();

        // Game ends
        system.endSession(sessionId);
        assert session.getState() == SessionState.FINISHED;
        System.out.println("Session finished");

        Thread.sleep(100); // let async events flush
        system.shutdown();
        System.out.println("--- Scenario A complete ---");
    }

    // -----------------------------------------------------------------------
    // Scenario B: Matchmaking Queue → Auto-pairing → Session
    // -----------------------------------------------------------------------

    private static void scenarioB_matchmaking() throws InterruptedException {
        System.out.println("--- Scenario B: Matchmaking ---");

        CountDownLatch matchLatch = new CountDownLatch(1);
        AtomicReference<MatchResult> matchRef = new AtomicReference<>();

        MultiplayerSessionSystem system = new MultiplayerSessionSystem(
                new lld.multiplayer.lobby.FirstAvailableStrategy(),
                matchResult -> {
                    matchRef.set(matchResult);
                    matchLatch.countDown();
                },
                100 // fast tick interval for demo
        );

        system.subscribe(SessionEventType.SESSION_STARTED,
                e -> System.out.println("[EVENT] SESSION_STARTED session="
                        + e.getSessionId().substring(0, 8)));

        SessionConfig config = SessionConfig.builder("CHESS")
                .minPlayers(2).maxPlayers(2)
                .sessionTtlSeconds(300)
                .build();

        Player carol = new Player("Carol");
        Player dave  = new Player("Dave");

        System.out.println("Carol and Dave enter matchmaking queue...");
        system.enqueueForMatchmaking(carol, config);
        system.enqueueForMatchmaking(dave,  config);

        boolean matched = matchLatch.await(3, TimeUnit.SECONDS);
        if (!matched) {
            System.out.println("ERROR: matchmaking did not fire within 3 seconds");
            system.shutdown();
            return;
        }

        MatchResult result = matchRef.get();
        System.out.println("Match found! players=" + result.getMatchedPlayers().stream()
                .map(Player::getDisplayName).toList());

        String sessionId = result.getSession().getSessionId();
        // Start the session (matchmaker leaves it in WAITING_FOR_PLAYERS so the gateway can
        // perform a ready-check handshake; here we skip straight to start)
        system.startSession(sessionId);

        Session session = system.getSession(sessionId).orElseThrow();
        assert session.getState() == SessionState.ACTIVE
                : "Expected ACTIVE, got " + session.getState();
        System.out.println("Session active: " + session.getState());

        system.endSession(sessionId);
        System.out.println("Session finished: " + session.getState());

        Thread.sleep(100);
        system.shutdown();
        System.out.println("--- Scenario B complete ---");
    }
}
