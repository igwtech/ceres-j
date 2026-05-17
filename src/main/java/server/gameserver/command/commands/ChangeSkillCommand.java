package server.gameserver.command.commands;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.command.CommandContext;
import server.gameserver.command.CommandResult;
import server.gameserver.command.GmCommand;
import server.gameserver.packets.server_udp.ForcedZoning;

/**
 * {@code !changeskill <subskillIndex> <level>} — set a subskill level
 * and force CharInfo redelivery via {@link ForcedZoning}.
 *
 * <p>This is the project-canonical lever for changing HUD pool maxes:
 * HLT (27), ATL (20), END (21), PSU (32) derive HLT/STA/PSI pool
 * maxima client-side from CharInfo Section 4 (see project memory
 * {@code hud_pool_path_confirmed}). Mutating the subskill and
 * re-zoning forces the client to recompute. Equivalent to the legacy
 * {@code !setsub} / {@code !setmaxhp} chat commands, now first-class.
 */
public final class ChangeSkillCommand implements GmCommand {

    @Override
    public String name() {
        return "changeskill";
    }

    @Override
    public String[] aliases() {
        return new String[]{"setskill", "setsub"};
    }

    @Override
    public int requiredGmLevel() {
        return Account.GM_GAMEMASTER;
    }

    @Override
    public String usage() {
        return "<subskillIndex> <level>  (e.g. 27 200 = HLT)";
    }

    @Override
    public String description() {
        return "Set a subskill level (re-zones to refresh HUD pools).";
    }

    @Override
    public CommandResult execute(CommandContext ctx) {
        Player pl = ctx.player();
        PlayerCharacter pc = ctx.character();
        if (pl == null || pc == null) {
            return CommandResult.error("no character attached");
        }
        int idx = ctx.intArg(0);
        int level = ctx.intArg(1);
        if (idx < 0 || idx > 45) {
            return CommandResult.error(
                    "subskill index out of range (0..45): " + idx);
        }
        level = Math.max(0, Math.min(200, level));
        pc.setSubskillLVL(idx, level);
        pl.send(new ForcedZoning(pl, pl.getMapID()));
        return CommandResult.ok("Subskill[" + idx + "] = " + level
                + ". Re-zoning to refresh HUD.");
    }
}
