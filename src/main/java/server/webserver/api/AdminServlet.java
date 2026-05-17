package server.webserver.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import server.tools.Config;

/**
 * {@code POST /api/admin} — secured admin command endpoint.
 *
 * <p>Request body (JSON):
 * <pre>{ "command": "set_hp", "params": { "player": "Runner", "hp": 500 } }</pre>
 *
 * <p>Every request MUST carry a valid admin credential (see
 * {@link AdminAuth}). Unlike the collaborator's prototype this endpoint
 * is authenticated by default and fails closed when no admin secret is
 * configured.
 *
 * <p>Responses are JSON {@code { "ok": bool, "message": str, "data": {} }}.
 * Status codes: 200 ok, 400 bad params, 401 bad/missing credential,
 * 403 API disabled (no secret configured), 404 target not found,
 * 405 wrong method.
 */
public class AdminServlet extends HttpServlet {

    private static final Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        addCorsHeaders(resp);

        // Parse body first so a "token" field can also carry the secret.
        JsonObject body;
        try {
            body = readJson(req);
        } catch (RuntimeException e) {
            write(resp, 400, false, "invalid JSON body", null);
            return;
        }

        String headerToken = req.getHeader("X-Admin-Token");
        String bodyToken = body != null && body.has("token")
                && !body.get("token").isJsonNull()
                ? body.get("token").getAsString() : null;
        String token = headerToken != null ? headerToken : bodyToken;
        String acctUser = req.getHeader("X-Admin-Account");
        String acctPass = req.getHeader("X-Admin-Pass");

        AdminAuth.Decision auth =
                AdminAuth.authorize(token, acctUser, acctPass);
        if (auth == AdminAuth.Decision.DISABLED) {
            write(resp, 403, false,
                    "admin API disabled: no WebAdminToken / admin account "
                    + "configured", null);
            return;
        }
        if (auth == AdminAuth.Decision.UNAUTHORIZED) {
            resp.setHeader("WWW-Authenticate", "X-Admin-Token");
            write(resp, 401, false, "invalid or missing admin credential",
                    null);
            return;
        }

        if (body == null) {
            write(resp, 400, false, "empty request body", null);
            return;
        }

        String command = body.has("command") && !body.get("command").isJsonNull()
                ? body.get("command").getAsString() : null;
        JsonObject params = body.has("params") && body.get("params").isJsonObject()
                ? body.getAsJsonObject("params") : new JsonObject();

        AdminCommands.Result result = AdminCommands.execute(command, params);
        write(resp, result.httpStatus, result.ok, result.message,
                result.data);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("application/json");
        addCorsHeaders(resp);
        write(resp, 405, false,
                "use POST with { command, params }", null);
    }

    @Override
    protected void doOptions(HttpServletRequest req,
            HttpServletResponse resp) {
        addCorsHeaders(resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    // ─── helpers ───────────────────────────────────────────────────

    private static JsonObject readJson(HttpServletRequest req)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = req.getReader()) {
            if (r == null) return null;
            char[] buf = new char[1024];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        if (sb.length() == 0) return null;
        return JsonParser.parseString(sb.toString()).getAsJsonObject();
    }

    private void write(HttpServletResponse resp, int status, boolean ok,
            String message, Map<String, Object> data) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("message", message);
        out.put("data", data == null ? new LinkedHashMap<>() : data);
        resp.setStatus(status);
        resp.getWriter().write(gson.toJson(out));
    }

    /**
     * Scoped CORS: only emit headers when an allow-list is configured in
     * {@code ceres.cfg} (default: none → same-origin only, the safe
     * default). Credentials header is intentionally never set.
     */
    private void addCorsHeaders(HttpServletResponse resp) {
        String origins = Config.getProperty("WebAdminCorsOrigins");
        if (origins == null || origins.isBlank()) return;
        resp.setHeader("Access-Control-Allow-Origin", origins.trim());
        resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers",
                "Content-Type, X-Admin-Token, X-Admin-Account, X-Admin-Pass");
    }
}
