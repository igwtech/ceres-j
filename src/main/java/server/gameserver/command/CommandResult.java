package server.gameserver.command;

/**
 * Outcome of a {@link GmCommand#execute(CommandContext)} invocation.
 *
 * <p>CMaNGOS distinguishes "command not found", "bad syntax",
 * "permission denied" and "handled" so the chat layer can react
 * differently (e.g. show usage on syntax error). We model the same
 * states with a small immutable value object plus a human-readable
 * message that the dispatcher echoes back to the player.
 */
public final class CommandResult {

    /** Why a command invocation ended the way it did. */
    public enum Status {
        /** Command ran and changed state (or intentionally did nothing). */
        OK,
        /** Arguments were missing or malformed; {@link #message} is the usage. */
        BAD_SYNTAX,
        /** Caller's GM level is below the command's requirement. */
        DENIED,
        /** No command registered under the given name. */
        NOT_FOUND,
        /** Command was found but failed at runtime (bad target, etc.). */
        ERROR
    }

    private final Status status;
    private final String message;

    private CommandResult(Status status, String message) {
        this.status = status;
        this.message = message;
    }

    public static CommandResult ok(String message) {
        return new CommandResult(Status.OK, message);
    }

    public static CommandResult badSyntax(String usage) {
        return new CommandResult(Status.BAD_SYNTAX, usage);
    }

    public static CommandResult denied(String message) {
        return new CommandResult(Status.DENIED, message);
    }

    public static CommandResult notFound(String message) {
        return new CommandResult(Status.NOT_FOUND, message);
    }

    public static CommandResult error(String message) {
        return new CommandResult(Status.ERROR, message);
    }

    public Status status() {
        return status;
    }

    public String message() {
        return message;
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    @Override
    public String toString() {
        return status + ": " + message;
    }
}
