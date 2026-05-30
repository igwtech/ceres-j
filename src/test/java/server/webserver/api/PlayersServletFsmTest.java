package server.webserver.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.Gson;

import server.gameserver.Player;
import server.gameserver.PlayerManager;
import server.gameserver.packets.client_udp.ReliableAck08;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.state.ClientState;
import server.gameserver.state.ClientStateMachine;

/**
 * Unit + functional tests for the FSM diagnostics added to
 * {@link PlayersServlet} in task #244.
 *
 * <p>The unit tier exercises {@link PlayersServlet#buildFsmSnapshot}
 * directly: state name, ms-in-state, ack pointer, transition log
 * shape, and the {@code MAX_TRANSITION_ENTRIES} bound.
 *
 * <p>The functional tier stands up an embedded Jetty against
 * {@code /api/players} and verifies the FSM block is present in
 * the JSON response, contains the live state, and reflects an
 * observed {@code ReliableAck08} on the wire.
 */
public class PlayersServletFsmTest {

    private static final Gson gson = new Gson();

    @SuppressWarnings("unchecked")
    private static LinkedList<Player> playerList() {
        try {
            Field f = PlayerManager.class.getDeclaredField("playerList");
            f.setAccessible(true);
            return (LinkedList<Player>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private Server server;
    private int port;
    private LinkedList<Player> snapshot;
    private Player runner;
    private final HttpClient http = HttpClient.newHttpClient();

    @Before
    public void setUp() throws Exception {
        snapshot = new LinkedList<>(playerList());
        playerList().clear();
        runner = PacketTestFixture.newPlayer();
        runner.getCharacter().setName("Runner");
        runner.setloggedin();
        playerList().add(runner);

        server = new Server();
        ServerConnector c = new ServerConnector(server);
        c.setHost("127.0.0.1");
        c.setPort(0); // ephemeral
        server.addConnector(c);
        ServletContextHandler ctx =
                new ServletContextHandler(ServletContextHandler.SESSIONS);
        ctx.setContextPath("/");
        ctx.addServlet(new ServletHolder(new PlayersServlet()),
                "/api/players");
        server.setHandler(ctx);
        server.start();
        port = c.getLocalPort();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.stop();
        playerList().clear();
        playerList().addAll(snapshot);
    }

    // ──────────────────────────────────────── unit: snapshot shape

    @Test
    public void snapshotOnFreshFsmIsAllDefaults() {
        ClientStateMachine fsm = new ClientStateMachine();
        Map<String, Object> snap = PlayersServlet.buildFsmSnapshot(fsm);
        assertEquals("PRE_LOGIN", snap.get("state"));
        // -1 sentinel for "no ack observed yet" — gson serialises
        // this as a Long when we round-trip JSON, so allow both.
        Object seq = snap.get("lastConfirmedReliableSeq");
        assertTrue("expected -1 seq sentinel on fresh fsm, got " + seq,
                seq.equals(-1) || seq.equals(-1L));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> txs =
                (List<Map<String, Object>>) snap.get("transitions");
        assertNotNull(txs);
        assertTrue("fresh fsm has no transitions yet",
                txs.isEmpty());
    }

    @Test
    public void snapshotNullFsmIsGracefulNoCrash() {
        Map<String, Object> snap = PlayersServlet.buildFsmSnapshot(null);
        assertNull(snap.get("state"));
    }

    @Test
    public void snapshotIncludesTransitions() {
        ClientStateMachine fsm = new ClientStateMachine();
        fsm.transition(ClientState.WORLDENTRY_BURST,
                "WorldEntryEvent: begin");
        fsm.transition(ClientState.IN_WORLD,
                "WorldEntryEvent: complete");

        Map<String, Object> snap = PlayersServlet.buildFsmSnapshot(fsm);
        assertEquals("IN_WORLD", snap.get("state"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> txs =
                (List<Map<String, Object>>) snap.get("transitions");
        assertEquals(2, txs.size());
        assertEquals("PRE_LOGIN", txs.get(0).get("from"));
        assertEquals("WORLDENTRY_BURST", txs.get(0).get("to"));
        assertEquals("WorldEntryEvent: begin", txs.get(0).get("reason"));
        assertEquals("WORLDENTRY_BURST", txs.get(1).get("from"));
        assertEquals("IN_WORLD", txs.get(1).get("to"));
    }

    @Test
    public void snapshotTransitionsCappedAtMaxEntries() {
        // The full transition ring inside the FSM is 64 entries
        // (ClientStateMachine.TRANSITION_LOG_CAPACITY); the JSON
        // payload should cap to PlayersServlet.MAX_TRANSITION_ENTRIES
        // to keep responses small in long sessions.
        ClientStateMachine fsm = new ClientStateMachine();
        int totalTransitions = PlayersServlet.MAX_TRANSITION_ENTRIES + 10;
        for (int i = 0; i < totalTransitions; i++) {
            // toggle between two states so every step is a real
            // transition (transition to the current state is a no-op).
            ClientState next = (i & 1) == 0
                    ? ClientState.IN_WORLD
                    : ClientState.CROSS_PENDING_LOAD;
            fsm.transition(next, "step " + i);
        }
        Map<String, Object> snap = PlayersServlet.buildFsmSnapshot(fsm);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> txs =
                (List<Map<String, Object>>) snap.get("transitions");
        assertEquals(PlayersServlet.MAX_TRANSITION_ENTRIES, txs.size());
        // Tail-most entry reason matches the last transition.
        assertEquals("step " + (totalTransitions - 1),
                txs.get(txs.size() - 1).get("reason"));
    }

    @Test
    public void snapshotReflectsAckPointerAdvance() {
        ClientStateMachine fsm = new ClientStateMachine();
        fsm.observeReliableAck(99);
        Map<String, Object> snap = PlayersServlet.buildFsmSnapshot(fsm);
        Object seq = snap.get("lastConfirmedReliableSeq");
        assertTrue("expected ack seq 99, got " + seq,
                seq.equals(99) || seq.equals(99L));
    }

    // ──────────────────────────────────────── functional: HTTP path

    @Test
    public void httpResponseIncludesFsmBlock() throws Exception {
        // Drive a couple of transitions + an ack so the snapshot has
        // real content to assert against.
        runner.getStateMachine().transition(
                ClientState.WORLDENTRY_BURST, "test-begin");
        runner.getStateMachine().transition(
                ClientState.IN_WORLD, "test-complete");
        new ReliableAck08(new byte[]{
                0x03, 0x00, 0x00, 0x08, 41, 0x00}).execute(runner);

        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/api/players"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body =
                gson.fromJson(resp.body(), List.class);
        assertEquals(1, body.size());
        Map<String, Object> player = body.get(0);
        assertEquals("Runner", player.get("characterName"));

        @SuppressWarnings("unchecked")
        Map<String, Object> fsm =
                (Map<String, Object>) player.get("fsm");
        assertNotNull("response must include fsm block", fsm);
        assertEquals("IN_WORLD", fsm.get("state"));
        // wire ack 41 → real ack seq 42.
        Object seq = fsm.get("lastConfirmedReliableSeq");
        assertTrue("HTTP-roundtrip seq should be 42, got " + seq,
                seq.equals(42.0) || seq.equals(42) || seq.equals(42L));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> txs =
                (List<Map<String, Object>>) fsm.get("transitions");
        assertEquals(2, txs.size());
        assertEquals("test-begin", txs.get(0).get("reason"));
        assertEquals("test-complete", txs.get(1).get("reason"));
    }
}
