package server.webserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.gameserver.state.ClientState;

/**
 * Functional test for the live FSM dashboard added in task #248.
 *
 * <p>Verifies:
 * <ol>
 *   <li>{@code fsm.html} is on the classpath at {@code webapp/} —
 *       caught at test time if a resource-filter rename or pom
 *       reorg accidentally drops it.</li>
 *   <li>An embedded Jetty wired the same way as
 *       {@link JettyWebServer} (static {@code webapp/} resource
 *       base + DefaultServlet) actually serves it on
 *       {@code GET /fsm.html}.</li>
 *   <li>The served HTML contains the contract markers it depends
 *       on — specifically the {@code fetch('/api/players')} call.
 *       If someone changes the endpoint path without updating the
 *       HTML, this catches it.</li>
 * </ol>
 *
 * <p>Doesn't actually load the page in a JS engine — just static
 * served-content shape. The dashboard's runtime behaviour (poll
 * loop, state-coloured chips) is JS-only; not worth a Selenium
 * dependency in this suite.
 */
public class FsmDashboardServingTest {

    private Server server;
    private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Before
    public void setUp() throws Exception {
        server = new Server();
        ServerConnector c = new ServerConnector(server);
        c.setHost("127.0.0.1");
        c.setPort(0); // ephemeral
        server.addConnector(c);

        ServletContextHandler ctx =
                new ServletContextHandler(ServletContextHandler.SESSIONS);
        ctx.setContextPath("/");
        Resource base = Resource.newClassPathResource("/webapp");
        assertTrue("webapp/ classpath resource must exist — "
                + "fsm.html lives under src/main/resources/webapp/",
                base != null && base.exists());
        ctx.setBaseResource(base);
        ServletHolder defaultHolder =
                new ServletHolder("default", DefaultServlet.class);
        defaultHolder.setInitParameter("dirAllowed", "false");
        defaultHolder.setInitParameter("welcomeFiles", "index.html");
        ctx.addServlet(defaultHolder, "/");

        server.setHandler(ctx);
        server.start();
        port = c.getLocalPort();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.stop();
    }

    @Test
    public void fsmHtmlIsServed() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/fsm.html"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("fsm.html must be served by the default servlet",
                200, resp.statusCode());
        String body = resp.body();
        assertTrue("served body must contain dashboard title",
                body.contains("Ceres-J FSM Live"));
    }

    @Test
    public void fsmHtmlPollsTheCorrectEndpoint() throws Exception {
        // Contract: the dashboard polls /api/players. If a future
        // refactor renames that endpoint without updating the
        // HTML, the dashboard would silently stop working. Pin
        // the contract here.
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/fsm.html"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        String body = resp.body();
        assertTrue("dashboard must fetch /api/players",
                body.contains("fetch('/api/players'"));
    }

    @Test
    public void fsmHtmlReferencesAllExpectedFsmStateChipClasses()
            throws Exception {
        // Pin the visual contract: every ClientState enum value
        // gets its own CSS chip class. If a state is added to
        // the enum but its chip class is missed in fsm.html, the
        // dashboard renders the new state without colour. This
        // test catches that drift.
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/fsm.html"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        String body = resp.body();

        for (ClientState s : ClientState.values()) {
            assertTrue("missing CSS chip class for ClientState."
                    + s.name() + " (expected .s-" + s.name()
                    + " in fsm.html)",
                    body.contains(".s-" + s.name() + " "));
        }
    }

    @Test
    public void fsmHtmlNotFoundReturns404() throws Exception {
        // Belt-and-suspenders: confirm a non-existent resource
        // under the same servlet returns 404, so our positive
        // test isn't accidentally matching a fall-through.
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port
                        + "/nonexistent_dashboard.html"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }
}
