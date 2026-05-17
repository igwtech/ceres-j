package server.gameserver.command.commands;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.command.CommandContext;
import server.gameserver.command.CommandResult;
import server.gameserver.command.GmCommand;
import server.gameserver.packets.server_udp.ForcedZoning;

/**
 * {@code !resetpos} — move the caller to the current zone's safe start
 * coordinates and re-stream zone state.
 *
 * <p>NC2 zones do not carry a per-zone designer "start marker" in the
 * Ceres-J world model yet, so "safe position" is defined as the zone
 * origin {@code (0,0,0)} (the documented fallback already used by
 * {@code Location.java} when a character has no stored coords). The
 * caller is then force-zoned into the same zone so the client reloads
 * the BSP at the reset position rather than rubber-banding.
 *
 * <p>TODO(#179): replace origin with the real per-zone spawn marker
 * once world.dat start points are imported.
 */
public final class ResetposCommand implements GmCommand {

    @Override
    public String name() {
        return "resetpos";
    }

    @Override
    public String[] aliases() {
        return new String[]{"unstuck"};
    }

    @Override
    public int requiredGmLevel() {
        return Account.GM_MODERATOR;
    }

    @Override
    public String usage() {
        return "(teleport to current zone's safe start point)";
    }

    @Override
    public String description() {
        return "Reset your position to the current zone start point.";
    }

    @Override
    public CommandResult execute(CommandContext ctx) {
        Player pl = ctx.player();
        PlayerCharacter pc = ctx.character();
        if (pl == null || pc == null) {
            return CommandResult.error("no character attached");
        }
        pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, 0);
        pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, 0);
        pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, 0);
        // Force the client to reload the zone at the reset coordinates.
        pl.send(new ForcedZoning(pl, pl.getMapID()));
        return CommandResult.ok(
                "Position reset to zone " + pl.getMapID()
                        + " start (0,0,0). Re-zoning.");
    }
}
