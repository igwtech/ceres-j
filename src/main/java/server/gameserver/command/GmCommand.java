package server.gameserver.command;

/**
 * A single GM/admin command, CMaNGOS-style.
 *
 * <p>CMaNGOS describes each command with a name, a required security
 * level, a help string and a handler function. We keep the same shape
 * so adding a command is "implement this interface + register it" with
 * no edits to the dispatcher.
 *
 * <p>Permission gating is enforced centrally by
 * {@link GmCommandRegistry#dispatch} using {@link #requiredGmLevel()};
 * implementations never need to re-check it.
 */
public interface GmCommand {

    /** Primary command keyword (no prefix), lower-case. */
    String name();

    /** Optional alternate keywords. Default: none. */
    default String[] aliases() {
        return new String[0];
    }

    /**
     * Minimum {@link server.database.accounts.Account} GM level the
     * caller must have. Use the {@code Account.GM_*} constants.
     */
    int requiredGmLevel();

    /** One-line usage string shown on bad syntax / in help. */
    String usage();

    /** Short description for the help listing. */
    default String description() {
        return usage();
    }

    /**
     * Run the command. Called only after the permission gate passed.
     * Implementations should never throw for user error — return
     * {@link CommandResult#badSyntax} / {@link CommandResult#error}
     * instead. An {@link IllegalArgumentException} thrown from a
     * {@link CommandContext} accessor is caught by the dispatcher and
     * converted to a BAD_SYNTAX reply, so accessor use is safe.
     */
    CommandResult execute(CommandContext ctx);
}
