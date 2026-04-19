package server.gameserver;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.packets.server_udp.ForcedZoning;
import server.gameserver.packets.server_udp.LocalChatMessage;
import server.tools.Out;

/**
 * Handles in-game admin/GM commands typed in chat.
 *
 * <p>Commands start with {@code /} and are intercepted before
 * broadcasting to the zone. All players can use basic commands;
 * GM-level commands can be gated by Account.adminLevel later.
 *
 * <p>Available commands:
 * <ul>
 *   <li>{@code /pos} — show current Y/Z/X coordinates</li>
 *   <li>{@code /warp <zoneId>} — teleport to a zone</li>
 *   <li>{@code /hp} — show current health/psi/stamina</li>
 *   <li>{@code /heal} — restore HP/PSI/STA to max</li>
 *   <li>{@code /kill} — set HP to 0 (for testing death)</li>
 *   <li>{@code /god} — toggle invincibility</li>
 *   <li>{@code /spawn <type> <x> <y> <z>} — spawn an NPC</li>
 *   <li>{@code /online} — list connected players</li>
 *   <li>{@code /help} — show available commands</li>
 * </ul>
 */
public class AdminCommandHandler {

    /**
     * Try to handle a chat message as a command.
     *
     * @return true if the message was a command (consumed), false if
     *         it should be broadcast as normal chat
     */
    public static boolean handle(Player pl, String message) {
        if (message == null || message.isEmpty()) return false;

        // Strip null bytes and trailing whitespace — readCString()
        // includes everything after offset 7 including null terminators.
        message = message.replace("\0", "").trim();
        if (!message.startsWith("!")) return false;
        String cmdLine = message.substring(1).trim();

        String[] parts = cmdLine.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
        case "pos":
            cmdPos(pl);
            return true;
        case "warp":
        case "zone":
            cmdWarp(pl, args);
            return true;
        case "hp":
        case "stats":
            cmdStats(pl);
            return true;
        case "heal":
            cmdHeal(pl);
            return true;
        case "kill":
            cmdKill(pl);
            return true;
        case "god":
            cmdGod(pl);
            return true;
        case "spawn":
            cmdSpawn(pl, args);
            return true;
        case "online":
        case "players":
            cmdOnline(pl);
            return true;
        case "help":
        case "?":
            cmdHelp(pl);
            return true;
        default:
            reply(pl, "Unknown command: !" + cmd + ". Type !help for list.");
            return true;
        }
    }

    private static void reply(Player pl, String msg) {
        pl.send(new LocalChatMessage(pl, "[Server] " + msg, 2));
    }

    private static void cmdPos(Player pl) {
        PlayerCharacter pc = pl.getCharacter();
        int y = pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE);
        int z = pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE);
        int x = pc.getMisc(PlayerCharacter.MISC_X_COORDINATE);
        reply(pl, "Position: Y=" + y + " Z=" + z + " X=" + x
                + " Zone=" + pl.getMapID());
    }

    private static void cmdWarp(Player pl, String args) {
        if (args.isEmpty()) {
            reply(pl, "Usage: /warp <zoneId>");
            return;
        }
        try {
            int zoneId = Integer.parseInt(args.split("\\s+")[0]);
            if (zoneId <= 0) {
                reply(pl, "Invalid zone ID: " + zoneId);
                return;
            }
            reply(pl, "Warping to zone " + zoneId + "...");
            pl.send(new ForcedZoning(pl, zoneId));
        } catch (NumberFormatException e) {
            reply(pl, "Invalid zone ID: " + args);
        }
    }

    private static void cmdStats(Player pl) {
        PlayerCharacter pc = pl.getCharacter();
        reply(pl, "HP: " + pc.getHealth() + "/" + pc.getMaxHealth()
                + " PSI: " + pc.getPsi() + "/" + pc.getMaxPsi()
                + " STA: " + pc.getStamina() + "/" + pc.getMaxStamina());
    }

    private static void cmdHeal(Player pl) {
        PlayerCharacter pc = pl.getCharacter();
        pc.setHealth(pc.getMaxHealth());
        pc.setPsi(pc.getMaxPsi());
        pc.setStamina(pc.getMaxStamina());
        reply(pl, "Healed to full.");
    }

    private static void cmdKill(Player pl) {
        reply(pl, "You died.");
        pl.die();
    }

    private static void cmdGod(Player pl) {
        // Toggle god mode via a simple flag on PlayerCharacter
        // For now just heal and report
        PlayerCharacter pc = pl.getCharacter();
        pc.setHealth(pc.getMaxHealth());
        reply(pl, "God mode: healed to full (toggle not yet implemented).");
    }

    private static void cmdSpawn(Player pl, String args) {
        if (args.isEmpty()) {
            reply(pl, "Usage: /spawn <type> [x y z]");
            return;
        }
        try {
            String[] p = args.split("\\s+");
            int type = Integer.parseInt(p[0]);
            PlayerCharacter pc = pl.getCharacter();
            int x = p.length > 1 ? Integer.parseInt(p[1]) : pc.getMisc(PlayerCharacter.MISC_X_COORDINATE);
            int y = p.length > 2 ? Integer.parseInt(p[2]) : pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE);
            int z = p.length > 3 ? Integer.parseInt(p[3]) : pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE);
            pl.getZone().addNPCtoZone(x, y, z, 100, type, 0);
            reply(pl, "Spawned NPC type=" + type + " at X=" + x + " Y=" + y + " Z=" + z);
        } catch (Exception e) {
            reply(pl, "Spawn failed: " + e.getMessage());
        }
    }

    private static void cmdOnline(Player pl) {
        java.util.List<Player> players = PlayerManager.getOnlinePlayers();
        if (players == null || players.isEmpty()) {
            reply(pl, "No players online.");
            return;
        }
        StringBuilder sb = new StringBuilder("Online (" + players.size() + "): ");
        for (Player p : players) {
            if (p.getCharacter() != null) {
                sb.append(p.getCharacter().getName()).append(", ");
            }
        }
        reply(pl, sb.toString());
    }

    private static void cmdHelp(Player pl) {
        reply(pl, "Commands: !pos !warp !hp !heal !kill !god !spawn !online !help");
    }
}
