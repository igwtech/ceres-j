package server.webserver.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.gson.Gson;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.PlayerManager;
import server.gameserver.Zone;

/**
 * GET /api/players
 * Returns JSON array of connected players with character info.
 */
public class PlayersServlet extends HttpServlet {

    private static final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        addCorsHeaders(resp);

        LinkedList<Player> players = PlayerManager.getPlayers();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Player player : players) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("account", player.getAccount().getUsername());
            entry.put("loggedIn", player.isloggedin());

            PlayerCharacter pc = player.getCharacter();
            if (pc != null) {
                entry.put("characterName", pc.getName());
                entry.put("location", pc.getMisc(PlayerCharacter.MISC_LOCATION));
                entry.put("faction", pc.getMisc(PlayerCharacter.MISC_FACTION));
                entry.put("class", pc.getMisc(PlayerCharacter.MISC_CLASS));
            }

            Zone zone = player.getZone();
            if (zone != null) {
                entry.put("zone", zone.getWorldname());
            }

            result.add(entry);
        }

        resp.getWriter().write(gson.toJson(result));
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
