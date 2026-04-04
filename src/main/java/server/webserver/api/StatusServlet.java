package server.webserver.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.gson.Gson;

import server.gameserver.PlayerManager;
import server.tools.Config;
import server.webserver.JettyWebServer;

/**
 * GET /api/status
 * Returns JSON with server name, uptime, player count, and version.
 */
public class StatusServlet extends HttpServlet {

    private static final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        addCorsHeaders(resp);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("serverName", Config.getProperty("ServerName"));
        status.put("version", Config.getProperty("ServerVersion"));
        status.put("uptime", System.currentTimeMillis() - JettyWebServer.getStartTime());
        status.put("playerCount", PlayerManager.getPlayers().size());

        resp.getWriter().write(gson.toJson(status));
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        addCorsHeaders(resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    private void addCorsHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }
}
