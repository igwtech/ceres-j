package server.gameserver.command.commands;

import server.database.accounts.Account;
import server.gameserver.command.CommandContext;
import server.gameserver.command.CommandResult;
import server.gameserver.command.GmCommand;
import server.gameserver.command.GmCommandRegistry;

/**
 * {@code !help} — list the commands the caller is allowed to use.
 *
 * <p>Filtered by the caller's GM level so a moderator never sees an
 * admin-only command (CMaNGOS behaviour). Available to everyone
 * ({@link Account#GM_PLAYER}).
 */
public final class HelpCommand implements GmCommand {

    private final GmCommandRegistry registry;

    public HelpCommand(GmCommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String[] aliases() {
        return new String[]{"?", "commands"};
    }

    @Override
    public int requiredGmLevel() {
        return Account.GM_PLAYER;
    }

    @Override
    public String usage() {
        return "(list available commands)";
    }

    @Override
    public CommandResult execute(CommandContext ctx) {
        int lvl = ctx.callerGmLevel();
        ctx.reply("Available commands (GM level " + lvl + "):");
        for (GmCommand c : registry.commands()) {
            if (lvl >= c.requiredGmLevel()) {
                ctx.reply("  !" + c.name() + " " + c.usage()
                        + "  [lvl " + c.requiredGmLevel() + "]");
            }
        }
        return CommandResult.ok("");
    }
}
