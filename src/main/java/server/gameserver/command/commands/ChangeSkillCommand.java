package server.gameserver.command.commands;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.command.CommandContext;
import server.gameserver.command.CommandResult;
import server.gameserver.command.GmCommand;
import server.gameserver.packets.server_udp.LiveCharInfoSync;

/**
 * {@code !changeskill <subskillIndex> <level>} — set a subskill level
 * and resync the HUD via {@link LiveCharInfoSync}.
 *
 * <p>This is the project-canonical lever for changing HUD pool maxes:
 * HLT (27), ATL (20), END (21), PSU (32) derive HLT/STA/PSI pool
 * maxima client-side from CharInfo Section 4 (see project memory
 * {@code hud_pool_path_confirmed}). Mutating the subskill then
 * re-emitting the full CharInfo through the Ghidra-pinned
 * {@code 0x03/0x2c} single-packet CHARSYS handler forces the client
 * to re-parse Section 4 and run {@code FUN_0080b8b0} (HUD recompute)
 * with <b>no zone reload</b> (task #194). Equivalent to the legacy
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
        return "Set a subskill level (live CHARSYS resync, no re-zone).";
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
        pl.send(LiveCharInfoSync.of(pl));
        return CommandResult.ok("Subskill[" + idx + "] = " + level
                + ". HUD resynced (live CHARSYS, no re-zone).");
    }
}
