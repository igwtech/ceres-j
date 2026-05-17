package server.gameserver.command.commands;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.database.worlds.WorldManager;
import server.gameserver.Player;
import server.gameserver.command.CommandContext;
import server.gameserver.command.CommandResult;
import server.gameserver.command.GmCommand;
import server.gameserver.packets.server_udp.ForcedZoning;

/**
 * {@code !zone <name|id> [x y z]} — change the caller's zone by either
 * a numeric world id or a human-readable zone <b>name</b>.
 *
 * <p>This re-homes the old legacy {@code AdminCommandHandler} switch
 * entry ({@code case "zone" -> cmdWarp}) into the modern registry
 * (task #182). The legacy {@code cmdWarp} was numeric-only and the
 * {@code zone}/{@code warp} aliases routed through it rejected names
 * like {@code pepper1}. Names now resolve via
 * {@link WorldManager#resolveByName(String)} (full path
 * {@code "pepper/pepper_p1"} or basename {@code "pepper_p1"} /
 * {@code "pepper1"}).
 *
 * <p>A zone change always force-zones (UDP {@link ForcedZoning}) so
 * the client reloads the destination BSP — this is the existing,
 * correct cross-zone relocation path and is intentionally left intact
 * (the task-#182 self-position fix is for the <em>same-zone</em> case
 * only; see {@link SelfRelocate} / {@link TpCommand}).
 *
 * <p>The legacy {@code warp}/{@code warpforce} entries are kept (they
 * are a different, boundary-only smooth-TCP mechanism) so removing
 * {@code zone} from the legacy switch causes no regression to other
 * legacy commands.
 */
public final class ZoneCommand implements GmCommand {

    @Override
    public String name() {
        return "zone";
    }

    @Override
    public String[] aliases() {
        return new String[]{"gozone"};
    }

    @Override
    public int requiredGmLevel() {
        return Account.GM_GAMEMASTER;
    }

    @Override
    public String usage() {
        return "<zoneName|zoneId> [x y z]";
    }

    @Override
    public String description() {
        return "Change zone by name or id (force-zones, splash).";
    }

    @Override
    public CommandResult execute(CommandContext ctx) {
        Player pl = ctx.player();
        PlayerCharacter pc = ctx.character();
        if (pl == null || pc == null) {
            return CommandResult.error("no character attached");
        }
        if (ctx.argCount() == 0) {
            return CommandResult.badSyntax(
                    "missing zone name or id");
        }

        String token = ctx.arg(0);
        int zoneId = resolveZone(token);
        if (zoneId <= 0) {
            return CommandResult.error(
                    "Unknown zone '" + token
                    + "'. Use a numeric id or a known zone name "
                    + "(e.g. pepper_p1 / pepper1 / pepper/pepper_p1).");
        }

        int x = ctx.argCount() > 1 ? ctx.intArg(1)
                : pc.getMisc(PlayerCharacter.MISC_X_COORDINATE);
        int y = ctx.argCount() > 2 ? ctx.intArg(2)
                : pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE);
        int z = ctx.argCount() > 3 ? ctx.intArg(3)
                : pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE);

        pc.setMisc(PlayerCharacter.MISC_LOCATION, zoneId);
        pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, x);
        pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, y);
        pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, z);
        // Cross-zone: force the client to reload the destination BSP.
        pl.send(new ForcedZoning(pl, zoneId));

        String resolvedName = WorldManager.getWorldname(zoneId);
        return CommandResult.ok("Zoning to "
                + (resolvedName != null ? resolvedName : "zone")
                + " (id " + zoneId + ") at ("
                + x + "," + y + "," + z + ").");
    }

    /**
     * Resolve a CLI token to a world id. A bare decimal/0x-hex number
     * is taken literally; anything else is looked up by name.
     */
    static int resolveZone(String token) {
        if (token == null) {
            return -1;
        }
        String t = token.trim();
        try {
            int id = t.startsWith("0x") || t.startsWith("0X")
                    ? (int) Long.parseLong(t.substring(2), 16)
                    : Integer.parseInt(t);
            return id;
        } catch (NumberFormatException notNumeric) {
            return WorldManager.resolveByName(t);
        }
    }
}
