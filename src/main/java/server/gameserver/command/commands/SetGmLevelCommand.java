package server.gameserver.command.commands;

import server.database.accounts.Account;
import server.database.accounts.AccountManager;
import server.gameserver.Player;
import server.gameserver.command.CommandContext;
import server.gameserver.command.CommandResult;
import server.gameserver.command.GmCommand;

/**
 * {@code !setgmlevel <player> <level>} — set another account's
 * CMaNGOS-style GM authority level and persist it (schema v8
 * {@code accounts.gm_level} column).
 *
 * <p>Requires {@link Account#GM_ADMIN}: this affects other accounts.
 * A caller can never grant a level above their own (CMaNGOS rule —
 * no privilege escalation). The target must be online so we can
 * resolve their account; the change is written through
 * {@link AccountManager#saveAccount(Account)} so it survives restart.
 */
public final class SetGmLevelCommand implements GmCommand {

    @Override
    public String name() {
        return "setgmlevel";
    }

    @Override
    public String[] aliases() {
        return new String[]{"gmlevel", "setgm"};
    }

    @Override
    public int requiredGmLevel() {
        return Account.GM_ADMIN;
    }

    @Override
    public String usage() {
        return "<player> <level 0..3>";
    }

    @Override
    public String description() {
        return "Set another player's GM authority level (persisted).";
    }

    @Override
    public CommandResult execute(CommandContext ctx) {
        String targetName = ctx.arg(0);
        int level = ctx.intArg(1);
        if (level < 0 || level > Account.GM_ADMIN) {
            return CommandResult.error(
                    "level must be 0.." + Account.GM_ADMIN);
        }
        if (level > ctx.callerGmLevel()) {
            return CommandResult.denied(
                    "You cannot grant a GM level above your own ("
                            + ctx.callerGmLevel() + ").");
        }

        Player target = TpCommand.findOnlineByName(targetName);
        if (target == null || target.getAccount() == null) {
            return CommandResult.error(
                    "No online player named '" + targetName + "'.");
        }
        Account acc = target.getAccount();
        acc.setGmLevel(level);
        try {
            AccountManager.saveAccount(acc);
        } catch (RuntimeException e) {
            return CommandResult.error(
                    "GM level set in memory but persist failed: "
                            + e.getMessage());
        }
        return CommandResult.ok("GM level of " + targetName
                + " set to " + level + " (persisted).");
    }
}
