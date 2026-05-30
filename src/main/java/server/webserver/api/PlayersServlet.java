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
import server.gameserver.state.ClientStateMachine;

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

            // FSM diagnostics (task #244). Live view of the per-Player
            // ClientStateMachine landed in #239: helps diagnose
            // zone-cross hangs (task #172) and other in-flight
            // transitions when a live client is attached. Cheap —
            // snapshot is a defensive copy of a small ring (≤64).
            entry.put("fsm", buildFsmSnapshot(player.getStateMachine()));

            result.add(entry);
        }

        resp.getWriter().write(gson.toJson(result));
    }

    /**
     * Build a serializable snapshot of a {@link ClientStateMachine}.
     * Visible for unit tests. Returns:
     * <pre>
     *   {
     *     "state": "IN_WORLD",
     *     "timeInStateMs": 1234,
     *     "lastConfirmedReliableSeq": 42,    // -1 if none yet
     *     "transitions": [
     *       {"from": "PRE_LOGIN", "to": "WORLDENTRY_BURST",
     *        "reason": "WorldEntryEvent: begin", "atRealtimeMs": …},
     *       …
     *     ]
     *   }
     * </pre>
     * The transition list is bounded to {@code MAX_TRANSITION_ENTRIES}
     * to keep the payload reasonable when a long-running session has
     * accumulated many transitions.
     */
    static Map<String, Object> buildFsmSnapshot(ClientStateMachine fsm) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (fsm == null) {
            out.put("state", null);
            return out;
        }
        out.put("state", fsm.getState().name());
        out.put("timeInStateMs", fsm.timeInStateMs());
        out.put("lastConfirmedReliableSeq",
                fsm.lastConfirmedReliableSeq());
        List<ClientStateMachine.Transition> log = fsm.recentTransitions();
        int from = Math.max(0, log.size() - MAX_TRANSITION_ENTRIES);
        List<Map<String, Object>> txs = new ArrayList<>();
        for (int i = from; i < log.size(); i++) {
            ClientStateMachine.Transition t = log.get(i);
            Map<String, Object> txEntry = new LinkedHashMap<>();
            txEntry.put("from", t.from.name());
            txEntry.put("to", t.to.name());
            txEntry.put("reason", t.reason);
            txEntry.put("atRealtimeMs", t.atRealtimeMs);
            txs.add(txEntry);
        }
        out.put("transitions", txs);
        return out;
    }

    /** Bound on per-player transition entries in the JSON payload.
     *  Keeps the payload small for long sessions; the underlying
     *  ring is bounded by {@link
     *  ClientStateMachine#TRANSITION_LOG_CAPACITY}. */
    static final int MAX_TRANSITION_ENTRIES = 20;

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
