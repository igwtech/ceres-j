package server.gameserver.command.commands;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.PlayerManager;
import server.gameserver.command.CommandContext;
import server.gameserver.command.CommandResult;
import server.gameserver.command.GmCommand;
import server.gameserver.packets.server_udp.ForcedZoning;

/**
 * {@code !tp} — teleport the caller.
 *
 * <p>Three forms:
 * <ul>
 *   <li>{@code !tp <x> <y> <z>} — move to coordinates in the current
 *       zone.</li>
 *   <li>{@code !tp zone <zoneId> [x y z]} — change zone (optionally to
 *       coordinates), force-zoning so the client reloads the BSP.</li>
 *   <li>{@code !tp to <playerName>} — teleport to an online player's
 *       current zone + coordinates (uses {@link PlayerManager}).</li>
 * </ul>
 */
public final class TpCommand implements GmCommand {

    @Override
    public String name() {
        return "tp";
    }

    @Override
    public String[] aliases() {
        return new String[]{"teleport", "goto"};
    }

    @Override
    public int requiredGmLevel() {
        return Account.GM_GAMEMASTER;
    }

    @Override
    public String usage() {
        return "<x> <y> <z> | zone <id> [x y z] | to <player>";
    }

    @Override
    public String description() {
        return "Teleport to coordinates, a zone, or another player.";
    }

    @Override
    public CommandResult execute(CommandContext ctx) {
        Player pl = ctx.player();
        PlayerCharacter pc = ctx.character();
        if (pl == null || pc == null) {
            return CommandResult.error("no character attached");
        }
        if (ctx.argCount() == 0) {
            return CommandResult.badSyntax("missing arguments");
        }

        String first = ctx.arg(0).toLowerCase();

        if (first.equals("to")) {
            String targetName = ctx.argsFrom(1);
            Player target = findOnlineByName(targetName);
            if (target == null || target.getCharacter() == null) {
                return CommandResult.error(
                        "No online player named '" + targetName + "'.");
            }
            PlayerCharacter tpc = target.getCharacter();
            int zone = tpc.getMisc(PlayerCharacter.MISC_LOCATION);
            int curZone = pc.getMisc(PlayerCharacter.MISC_LOCATION);
            moveTo(pc, zone,
                    tpc.getMisc(PlayerCharacter.MISC_X_COORDINATE),
                    tpc.getMisc(PlayerCharacter.MISC_Y_COORDINATE),
                    tpc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
            if (zone == curZone) {
                // Same zone: no BSP reload — emit the retail-validated
                // self-position pair so the client actually moves.
                SelfRelocate.inZone(pl);
            } else {
                pl.send(new ForcedZoning(pl, zone));
            }
            return CommandResult.ok("Teleported to "
                    + tpc.getName() + " (zone " + zone + ").");
        }

        if (first.equals("zone")) {
            int zone = ctx.intArg(1);
            int x = ctx.argCount() > 2 ? ctx.intArg(2)
                    : pc.getMisc(PlayerCharacter.MISC_X_COORDINATE);
            int y = ctx.argCount() > 3 ? ctx.intArg(3)
                    : pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE);
            int z = ctx.argCount() > 4 ? ctx.intArg(4)
                    : pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE);
            moveTo(pc, zone, x, y, z);
            pl.send(new ForcedZoning(pl, zone));
            return CommandResult.ok("Teleported to zone " + zone
                    + " (" + x + "," + y + "," + z + ").");
        }

        // bare coordinates within the current zone — no zone change,
        // so do NOT ForcedZoning (that triggers a full splash + BSP
        // reload and the pre-fix code never moved the player at all
        // because setMisc alone sends nothing to the client). Emit the
        // retail-validated self-position pair instead.
        int x = ctx.intArg(0);
        int y = ctx.intArg(1);
        int z = ctx.intArg(2);
        int zone = pc.getMisc(PlayerCharacter.MISC_LOCATION);
        moveTo(pc, zone, x, y, z);
        SelfRelocate.inZone(pl);
        return CommandResult.ok("Teleported to (" + x + ","
                + y + "," + z + ") in zone " + zone + ".");
    }

    private static void moveTo(PlayerCharacter pc, int zone,
                               int x, int y, int z) {
        pc.setMisc(PlayerCharacter.MISC_LOCATION, zone);
        pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, x);
        pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, y);
        pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, z);
    }

    /** Case-insensitive online-player lookup by character name. */
    public static Player findOnlineByName(String name) {
        if (name == null || name.isEmpty()) return null;
        for (Player p : PlayerManager.getOnlinePlayers()) {
            PlayerCharacter c = p.getCharacter();
            if (c != null && name.equalsIgnoreCase(c.getName())) {
                return p;
            }
        }
        return null;
    }
}
