package server.webserver;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;

import server.exceptions.StartupException;
import server.tools.Config;
import server.tools.Out;
import server.webserver.api.AccountsServlet;
import server.webserver.api.PlayersServlet;
import server.webserver.api.StatusServlet;

/**
 * Embedded Jetty web server that serves:
 * - REST API endpoints under /api/*
 * - Static Vue 3 dashboard from classpath resources
 */
public final class JettyWebServer {

    private static Server server;
    private static long startTime;

    public static void init() throws StartupException {
        if (server != null) return;
        if (!"true".equals(Config.getProperty("StartWebServer"))) return;

        Out.writeln(Out.Info, "Starting Jetty HTTP Server");

        int port = Integer.parseInt(Config.getProperty("WebServerPort"));
        startTime = System.currentTimeMillis();

        server = new Server(port);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // API servlets
        context.addServlet(new ServletHolder("status", new StatusServlet()), "/api/status");
        context.addServlet(new ServletHolder("players", new PlayersServlet()), "/api/players");
        context.addServlet(new ServletHolder("accounts", new AccountsServlet()), "/api/accounts");

        // Static files from classpath (webapp/)
        Resource baseResource = Resource.newClassPathResource("/webapp");
        if (baseResource != null) {
            context.setBaseResource(baseResource);
            ServletHolder defaultHolder = new ServletHolder("default", DefaultServlet.class);
            defaultHolder.setInitParameter("dirAllowed", "false");
            defaultHolder.setInitParameter("welcomeFiles", "index.html");
            context.addServlet(defaultHolder, "/");
        } else {
            Out.writeln(Out.Warning, "Webapp resources not found on classpath, dashboard will not be available");
        }

        server.setHandler(context);

        try {
            server.start();
            Out.writeln(Out.Info, "Jetty HTTP Server started on port " + port);
        } catch (Exception e) {
            server = null;
            throw new StartupException("Failed to start Jetty HTTP Server: " + e.getMessage());
        }
    }

    public static void stopServer() {
        if (server != null) {
            Out.writeln(Out.Info, "Stopping Jetty HTTP Server");
            try {
                server.stop();
            } catch (Exception e) {
                Out.writeln(Out.Error, "Error stopping Jetty HTTP Server: " + e.getMessage());
            }
            server = null;
            Out.writeln(Out.Info, "Jetty HTTP Server stopped");
        }
    }

    public static long getStartTime() {
        return startTime;
    }

    public static boolean isRunning() {
        return server != null && server.isRunning();
    }
}
