package lld.multiplayer;

import lld.multiplayer.lobby.Lobby;
import lld.multiplayer.lobby.LobbyState;
import lld.multiplayer.matchmaking.MatchResult;
import lld.multiplayer.model.Player;
import lld.multiplayer.model.PlayerState;
import lld.multiplayer.model.Session;
import lld.multiplayer.model.SessionConfig;
import lld.multiplayer.model.SessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LobbyTest {

    private MultiplayerSessionSystem system;

    private static final SessionConfig CONFIG = SessionConfig.builder("TEST")
            .minPlayers(2).maxPlayers(3)
            .sessionTtlSeconds(300)
            .build();

    @BeforeEach
    void setUp() {
        system = new MultiplayerSessionSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    // -----------------------------------------------------------------------
    // Lobby entity tests
    // -----------------------------------------------------------------------

    @Test
    void lobby_startsOpenWithHost() {
        Player host = new Player("Host");
        Lobby lobby = new Lobby(host.getPlayerId(), CONFIG);
        lobby.addPlayer(host);

        assertEquals(LobbyState.OPEN, lobby.getState());
        assertEquals(1, lobby.getPlayerCount());
    }

    @Test
    void lobby_transitionsToFullAtMaxPlayers() {
        Player h = new Player("H");
        Player a = new Player("A");
        Player b = new Player("B");
        Lobby lobby = new Lobby(h.getPlayerId(), CONFIG);
        lobby.addPlayer(h);
        lobby.addPlayer(a);
        lobby.addPlayer(b);

        assertEquals(LobbyState.FULL, lobby.getState());
    }

    @Test
    void lobby_transitionsBackToOpenWhenPlayerLeaves() {
        Player h = new Player("H");
        Player a = new Player("A");
        Player b = new Player("B");
        Lobby lobby = new Lobby(h.getPlayerId(), CONFIG);
        lobby.addPlayer(h);
        lobby.addPlayer(a);
        lobby.addPlayer(b);

        assertEquals(LobbyState.FULL, lobby.getState());
        lobby.removePlayer(b);
        assertEquals(LobbyState.OPEN, lobby.getState());
    }

    @Test
    void lobby_rejectsDuplicatePlayers() {
        Player h = new Player("H");
        Lobby lobby = new Lobby(h.getPlayerId(), CONFIG);
        lobby.addPlayer(h);

        boolean addedAgain = lobby.addPlayer(h);
        assertFalse(addedAgain);
        assertEquals(1, lobby.getPlayerCount());
    }

    @Test
    void lobby_rejectsJoinWhenClosed() {
        Player h = new Player("H");
        Player a = new Player("A");
        Lobby lobby = new Lobby(h.getPlayerId(), CONFIG);
        lobby.addPlayer(h);
        lobby.close();

        boolean joined = lobby.addPlayer(a);
        assertFalse(joined);
    }

    // -----------------------------------------------------------------------
    // System-level lobby tests
    // -----------------------------------------------------------------------

    @Test
    void createLobby_hostIsAutomaticallyAdded() {
        Player host = new Player("Host");
        Lobby lobby = system.createLobby(host, CONFIG);

        assertEquals(1, lobby.getPlayerCount());
        assertTrue(system.getLobby(lobby.getLobbyId()).isPresent());
    }

    @Test
    void joinLobby_guestIsAdded() {
        Player host  = new Player("Host");
        Player guest = new Player("Guest");
        Lobby lobby = system.createLobby(host, CONFIG);

        boolean joined = system.joinLobby(lobby.getLobbyId(), guest);

        assertTrue(joined);
        assertEquals(2, lobby.getPlayerCount());
    }

    @Test
    void startLobbySession_createsActiveSession() {
        Player host  = new Player("Host");
        Player guest = new Player("Guest");
        Lobby lobby = system.createLobby(host, CONFIG);
        system.joinLobby(lobby.getLobbyId(), guest);

        MatchResult result = system.startLobbySession(lobby.getLobbyId());

        assertNotNull(result.getSession());
        Session session = system.getSession(result.getSession().getSessionId()).orElseThrow();
        assertEquals(SessionState.ACTIVE, session.getState());
        assertEquals(2, session.getPlayerCount());
        assertEquals(PlayerState.IN_SESSION, host.getState());
        assertEquals(PlayerState.IN_SESSION, guest.getState());
    }

    @Test
    void startLobbySession_closesLobby() {
        Player host  = new Player("Host");
        Player guest = new Player("Guest");
        Lobby lobby = system.createLobby(host, CONFIG);
        system.joinLobby(lobby.getLobbyId(), guest);

        system.startLobbySession(lobby.getLobbyId());

        assertEquals(LobbyState.CLOSED, lobby.getState());
        assertTrue(system.getLobby(lobby.getLobbyId()).isEmpty(),
                "Lobby should be removed from registry after session starts");
    }

    @Test
    void startLobbySession_throwsWhenNotEnoughPlayers() {
        Player host = new Player("Host");
        Lobby lobby = system.createLobby(host, CONFIG);

        assertThrows(IllegalStateException.class,
                () -> system.startLobbySession(lobby.getLobbyId()));
    }

    @Test
    void joinLobby_returnsFalseForUnknownLobby() {
        Player p = new Player("P");
        assertThrows(IllegalArgumentException.class,
                () -> system.joinLobby("no-such-lobby", p));
    }
}
