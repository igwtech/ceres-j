package server.webserver.api;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedList;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import server.gameserver.Player;
import server.gameserver.PlayerManager;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.tools.Config;

/**
 * Functional test: a real {@link AdminServlet} served by an embedded
 * Jetty bound to loopback, exercised over HTTP with
 * {@link HttpClient}. Verifies the full transport path —
 * auth rejection (missing/invalid token → 401, API-disabled → 403)
 * and an authenticated command actually mutating game state.
 */
public class AdminServletFunctionalTest {

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
    private String prevToken;
    private final HttpClient http = HttpClient.newHttpClient();

    @Before
    public void setUp() throws Exception {
        prevToken = Config.getProperty("WebAdminToken");
        Config.setProperty("WebAdminToken", "test-secret");

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
        ctx.addServlet(new ServletHolder(new AdminServlet()), "/api/admin");
        server.setHandler(ctx);
        server.start();
        port = c.getLocalPort();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.stop();
        playerList().clear();
        playerList().addAll(snapshot);
        Config.setProperty("WebAdminToken", prevToken);
    }

    private HttpResponse<String> post(String token, String json)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/admin"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("X-Admin-Token", token);
        return http.send(b.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void missingTokenReturns401() throws Exception {
        HttpResponse<String> r = post(null,
                "{\"command\":\"set_hp\",\"params\":{\"player\":\"Runner\",\"hp\":1}}");
        assertEquals(401, r.statusCode());
        assertFalse(gson.fromJson(r.body(), JsonObject.class)
                .get("ok").getAsBoolean());
    }

    @Test
    public void invalidTokenReturns401() throws Exception {
        HttpResponse<String> r = post("wrong-token",
                "{\"command\":\"set_hp\",\"params\":{\"player\":\"Runner\",\"hp\":1}}");
        assertEquals(401, r.statusCode());
    }

    @Test
    public void disabledApiReturns403() throws Exception {
        Config.setProperty("WebAdminToken", "");
        HttpResponse<String> r = post("anything",
                "{\"command\":\"save_db\",\"params\":{}}");
        assertEquals(403, r.statusCode());
    }

    @Test
    public void validTokenExecutesCommandAndMutatesState()
            throws Exception {
        runner.getCharacter().setHealth(10);
        HttpResponse<String> r = post("test-secret",
                "{\"command\":\"set_hp\",\"params\":{\"player\":\"Runner\",\"hp\":654}}");
        assertEquals(200, r.statusCode());
        JsonObject body = gson.fromJson(r.body(), JsonObject.class);
        assertTrue(body.get("ok").getAsBoolean());
        assertEquals(654, runner.getCharacter().getHealth());
    }

    @Test
    public void tokenInBodyAlsoAccepted() throws Exception {
        HttpResponse<String> r = post(null,
                "{\"token\":\"test-secret\",\"command\":\"save_db\",\"params\":{}}");
        assertEquals(200, r.statusCode());
    }

    @Test
    public void getMethodNotAllowed() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/admin"))
                .GET().build();
        HttpResponse<String> r = http.send(req,
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, r.statusCode());
    }

    @Test
    public void malformedJsonReturns400() throws Exception {
        HttpResponse<String> r = post("test-secret", "{ not json ");
        assertEquals(400, r.statusCode());
    }
}
