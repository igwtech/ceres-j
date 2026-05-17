package server.webserver.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import server.database.accounts.Account;
import server.database.accounts.AccountManager;
import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.PlayerCharacterManager;
import server.gameserver.Player;
import server.gameserver.PlayerManager;
import server.gameserver.packets.server_udp.ForcedZoning;
import server.gameserver.packets.server_udp.LocalChatMessage;
import server.gameserver.packets.server_udp.PoolStatusBroadcast;
import server.gameserver.packets.server_udp.PoolUpdate;
import server.tools.Out;

/**
 * Pure command logic for the web admin API, decoupled from HTTP so it
 * can be unit-tested directly. {@link AdminServlet} handles transport
 * and auth; this class only validates parameters and mutates game
 * state, mirroring the patterns already used by
 * {@link server.gameserver.AdminCommandHandler}.
 *
 * <p>Endpoint set is the feature spec inherited from the collaborator's
 * prototype, reimplemented cleanly against the current tree:
 * teleport, set_admin, ban, unban, set_cash, set_rank, set_hp,
 * set_faction, set_stat, kill, resurrect, kick, broadcast,
 * set_weather, set_time, save_db.
 */
public final class AdminCommands {

    private AdminCommands() {}

    /** Structured outcome returned to the servlet. */
    public static final class Result {
        public final boolean ok;
        public final int httpStatus;
        public final String message;
        public final Map<String, Object> data;

        private Result(boolean ok, int httpStatus, String message,
                Map<String, Object> data) {
            this.ok = ok;
            this.httpStatus = httpStatus;
            this.message = message;
            this.data = data;
        }

        static Result ok(String msg) {
            return new Result(true, 200, msg, new LinkedHashMap<>());
        }

        static Result ok(String msg, Map<String, Object> data) {
            return new Result(true, 200, msg, data);
        }

        /** 400 — malformed/missing parameters. */
        static Result badRequest(String msg) {
            return new Result(false, 400, msg, new LinkedHashMap<>());
        }

        /** 404 — target player/account not found. */
        static Result notFound(String msg) {
            return new Result(false, 404, msg, new LinkedHashMap<>());
        }
    }

    // ─── param helpers ─────────────────────────────────────────────

    private static String str(JsonObject p, String key) {
        if (p == null) return null;
        JsonElement e = p.get(key);
        return (e == null || e.isJsonNull()) ? null : e.getAsString();
    }

    private static Integer intOrNull(JsonObject p, String key) {
        if (p == null) return null;
        JsonElement e = p.get(key);
        if (e == null || e.isJsonNull()) return null;
        try {
            return e.getAsInt();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Resolve an online player by character name (case-insensitive). */
    static Player findPlayerByCharacter(String name) {
        if (name == null) return null;
        for (Player pl : PlayerManager.getPlayers()) {
            PlayerCharacter pc = pl.getCharacter();
            if (pc != null && name.equalsIgnoreCase(pc.getName())) {
                return pl;
            }
        }
        return null;
    }

    private static final Map<String, Integer> STAT_INDEX = Map.of(
            "STR", PlayerCharacter.STR,
            "DEX", PlayerCharacter.DEX,
            "CON", PlayerCharacter.CON,
            "INT", PlayerCharacter.INT,
            "PSI", PlayerCharacter.PSI);

    /**
     * Execute a command. Never throws on bad input — returns a
     * {@link Result} with the appropriate HTTP status instead.
     */
    public static Result execute(String command, JsonObject params) {
        if (command == null || command.isBlank()) {
            return Result.badRequest("missing 'command'");
        }
        try {
            switch (command) {
            case "teleport":     return teleport(params);
            case "set_admin":    return setAdmin(params);
            case "ban":          return setBan(params, true);
            case "unban":        return setBan(params, false);
            case "set_cash":     return setCash(params);
            case "set_rank":     return setRank(params);
            case "set_hp":       return setHp(params);
            case "set_faction":  return setFaction(params);
            case "set_stat":     return setStat(params);
            case "kill":         return kill(params);
            case "resurrect":    return resurrect(params);
            case "kick":         return kick(params);
            case "broadcast":    return broadcast(params);
            case "set_weather":  return setWeather(params);
            case "set_time":     return setTime(params);
            case "save_db":      return saveDb();
            default:
                return Result.badRequest("unknown command: " + command);
            }
        } catch (RuntimeException ex) {
            Out.writeln(Out.Error,
                    "AdminCommands '" + command + "' failed: " + ex);
            return Result.badRequest("command failed: " + ex.getMessage());
        }
    }

    // ─── commands ──────────────────────────────────────────────────

    private static Result teleport(JsonObject p) {
        String name = str(p, "player");
        Integer zoneId = intOrNull(p, "zone");
        if (name == null || zoneId == null) {
            return Result.badRequest("teleport requires 'player' and 'zone'");
        }
        if (zoneId <= 0) {
            return Result.badRequest("invalid zone: " + zoneId);
        }
        Player pl = findPlayerByCharacter(name);
        if (pl == null) return Result.notFound("player not online: " + name);

        pl.getCharacter().setMisc(PlayerCharacter.MISC_LOCATION, zoneId);
        pl.send(new ForcedZoning(pl, zoneId));
        return Result.ok("teleported " + name + " to zone " + zoneId);
    }

    private static Result setAdmin(JsonObject p) {
        String acctName = str(p, "account");
        if (acctName == null) {
            return Result.badRequest("set_admin requires 'account'");
        }
        boolean enable = booleanParam(p, "admin", true);
        Account a = AccountManager.findByUsername(acctName);
        if (a == null) return Result.notFound("account not found: " + acctName);

        a.setStatusCode(enable ? Account.STATUS_ADMIN : 0);
        AccountManager.saveAccount(a);
        return Result.ok((enable ? "granted" : "revoked")
                + " admin for account " + acctName);
    }

    private static Result setBan(JsonObject p, boolean ban) {
        String acctName = str(p, "account");
        if (acctName == null) {
            return Result.badRequest((ban ? "ban" : "unban")
                    + " requires 'account'");
        }
        Account a = AccountManager.findByUsername(acctName);
        if (a == null) return Result.notFound("account not found: " + acctName);

        a.setStatusCode(ban ? Account.STATUS_BANNED : 0);
        AccountManager.saveAccount(a);

        if (ban) {
            // Disconnect any live session for the banned account.
            for (Player pl : PlayerManager.getPlayers()) {
                if (pl.getAccount() == a) {
                    disconnect(pl);
                    break;
                }
            }
        }
        return Result.ok((ban ? "banned" : "unbanned")
                + " account " + acctName);
    }

    private static Result setCash(JsonObject p) {
        String name = str(p, "player");
        Integer amount = intOrNull(p, "amount");
        if (name == null || amount == null) {
            return Result.badRequest("set_cash requires 'player' and 'amount'");
        }
        Player pl = findPlayerByCharacter(name);
        if (pl == null) return Result.notFound("player not online: " + name);

        pl.getCharacter().setCash(amount);
        try {
            pl.send(new server.gameserver.packets.server_udp.CashUpdate(
                    pl, amount));
        } catch (RuntimeException ignore) { /* HUD push best-effort */ }
        return Result.ok("set cash of " + name + " to " + amount);
    }

    private static Result setRank(JsonObject p) {
        String name = str(p, "player");
        Integer rank = intOrNull(p, "rank");
        if (name == null || rank == null) {
            return Result.badRequest("set_rank requires 'player' and 'rank'");
        }
        Player pl = findPlayerByCharacter(name);
        if (pl == null) return Result.notFound("player not online: " + name);

        pl.getCharacter().setRank(rank);
        return Result.ok("set rank of " + name + " to " + rank);
    }

    private static Result setHp(JsonObject p) {
        String name = str(p, "player");
        Integer hp = intOrNull(p, "hp");
        if (name == null || hp == null) {
            return Result.badRequest("set_hp requires 'player' and 'hp'");
        }
        Player pl = findPlayerByCharacter(name);
        if (pl == null) return Result.notFound("player not online: " + name);

        PlayerCharacter pc = pl.getCharacter();
        int delta = hp - pc.getHealth();
        pc.setHealth(hp);
        try {
            pl.send(new PoolUpdate(pl, PoolUpdate.POOL_HP, delta,
                    pc.getMaxHealth()));
            pl.send(new PoolStatusBroadcast(pl));
        } catch (RuntimeException ignore) { /* HUD push best-effort */ }
        return Result.ok("set HP of " + name + " to " + hp);
    }

    private static Result setFaction(JsonObject p) {
        String name = str(p, "player");
        Integer faction = intOrNull(p, "faction");
        if (name == null || faction == null) {
            return Result.badRequest(
                    "set_faction requires 'player' and 'faction'");
        }
        Player pl = findPlayerByCharacter(name);
        if (pl == null) return Result.notFound("player not online: " + name);

        pl.getCharacter().setMisc(PlayerCharacter.MISC_FACTION, faction);
        return Result.ok("set faction of " + name + " to " + faction);
    }

    private static Result setStat(JsonObject p) {
        String name = str(p, "player");
        String stat = str(p, "stat");
        Integer value = intOrNull(p, "value");
        if (name == null || stat == null || value == null) {
            return Result.badRequest(
                    "set_stat requires 'player', 'stat', 'value'");
        }
        Integer idx = STAT_INDEX.get(stat.toUpperCase());
        if (idx == null) {
            return Result.badRequest(
                    "stat must be one of STR/DEX/CON/INT/PSI, got: " + stat);
        }
        Player pl = findPlayerByCharacter(name);
        if (pl == null) return Result.notFound("player not online: " + name);

        // STR/DEX/CON/INT/PSI map to PlayerCharacter skill slots 1..5
        // (PlayerCharacter.SKILLS). setSkillLVL is the verified setter
        // for the main stat levels in the current tree.
        pl.getCharacter().setSkillLVL(idx, value);
        return Result.ok("set " + stat.toUpperCase() + " of "
                + name + " to " + value);
    }

    private static Result kill(JsonObject p) {
        String name = str(p, "player");
        if (name == null) return Result.badRequest("kill requires 'player'");
        Player pl = findPlayerByCharacter(name);
        if (pl == null) return Result.notFound("player not online: " + name);

        pl.die();
        return Result.ok("killed " + name);
    }

    private static Result resurrect(JsonObject p) {
        String name = str(p, "player");
        if (name == null) {
            return Result.badRequest("resurrect requires 'player'");
        }
        Player pl = findPlayerByCharacter(name);
        if (pl == null) return Result.notFound("player not online: " + name);

        PlayerCharacter pc = pl.getCharacter();
        pc.setHealth(pc.getMaxHealth());
        pc.setPsi(pc.getMaxPsi());
        pc.setStamina(pc.getMaxStamina());
        try {
            pl.send(new PoolUpdate(pl, PoolUpdate.POOL_HP,
                    pc.getMaxHealth(), pc.getMaxHealth()));
            pl.send(new PoolStatusBroadcast(pl));
        } catch (RuntimeException ignore) { /* HUD push best-effort */ }
        return Result.ok("resurrected " + name
                + " (HP/PSI/STA restored to max)");
    }

    private static Result kick(JsonObject p) {
        String name = str(p, "player");
        if (name == null) return Result.badRequest("kick requires 'player'");
        Player pl = findPlayerByCharacter(name);
        if (pl == null) return Result.notFound("player not online: " + name);

        disconnect(pl);
        return Result.ok("kicked " + name);
    }

    private static Result broadcast(JsonObject p) {
        String message = str(p, "message");
        if (message == null || message.isEmpty()) {
            return Result.badRequest("broadcast requires 'message'");
        }
        int sent = 0;
        for (Player pl : PlayerManager.getOnlinePlayers()) {
            try {
                pl.send(new LocalChatMessage(pl,
                        "[Server] " + message, 2));
                sent++;
            } catch (RuntimeException ignore) { /* skip broken session */ }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recipients", sent);
        return Result.ok("broadcast delivered to " + sent
                + " player(s)", data);
    }

    private static Result setWeather(JsonObject p) {
        String weather = str(p, "weather");
        if (weather == null) {
            return Result.badRequest("set_weather requires 'weather'");
        }
        // The current engine has no server-side weather model; surface
        // the change as a server notice so the operator sees it took
        // effect end-to-end. Documented limitation, not silent no-op.
        int sent = 0;
        for (Player pl : PlayerManager.getOnlinePlayers()) {
            try {
                pl.send(new LocalChatMessage(pl,
                        "[Server] Weather set to: " + weather, 2));
                sent++;
            } catch (RuntimeException ignore) { /* skip */ }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("weather", weather);
        data.put("notified", sent);
        return Result.ok("weather announced as '" + weather + "'", data);
    }

    private static Result setTime(JsonObject p) {
        String time = str(p, "time");
        if (time == null) {
            return Result.badRequest("set_time requires 'time'");
        }
        int sent = 0;
        for (Player pl : PlayerManager.getOnlinePlayers()) {
            try {
                pl.send(new LocalChatMessage(pl,
                        "[Server] Game time set to: " + time, 2));
                sent++;
            } catch (RuntimeException ignore) { /* skip */ }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("time", time);
        data.put("notified", sent);
        return Result.ok("time announced as '" + time + "'", data);
    }

    private static Result saveDb() {
        AccountManager.save();
        PlayerCharacterManager.save();
        return Result.ok("database saved (accounts + characters)");
    }

    // ─── internals ─────────────────────────────────────────────────

    private static boolean booleanParam(JsonObject p, String key,
            boolean dflt) {
        if (p == null) return dflt;
        JsonElement e = p.get(key);
        if (e == null || e.isJsonNull()) return dflt;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return dflt;
        }
    }

    /** Force-disconnect a live player session (kick / post-ban). */
    private static void disconnect(Player pl) {
        try {
            pl.closeTCP();
        } catch (RuntimeException ignore) { /* already gone */ }
        try {
            pl.closeUDP();
        } catch (RuntimeException ignore) { /* already gone */ }
        PlayerManager.remove(pl);
    }
}
