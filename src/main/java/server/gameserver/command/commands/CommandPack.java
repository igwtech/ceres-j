package server.gameserver.command.commands;

import server.gameserver.command.GmCommandRegistry;

/**
 * Single place that wires every task-#179 {@link
 * server.gameserver.command.GmCommand} into a registry.
 *
 * <p>Adding a command is "create the class + add one
 * {@code reg.register(...)} line here" — the dispatcher, permission
 * gate and both invocation paths (chat + native {@code 0x1f/0x06}
 * UDP) need no edits. {@code defaultRegistry()} calls this once.
 */
public final class CommandPack {

    private CommandPack() {}

    public static void registerAll(GmCommandRegistry reg) {
        reg.register(new HelpCommand(reg));
        reg.register(new NoclipCommand());
        reg.register(new ResetposCommand());
        reg.register(new TpCommand());
        reg.register(new GiveItemCommand());
        reg.register(new ChangeSkillCommand());
        reg.register(new GiveMoneyCommand());
        reg.register(new SetGmLevelCommand());
    }
}
