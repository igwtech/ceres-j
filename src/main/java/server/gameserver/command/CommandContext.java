package server.gameserver.command;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;

/**
 * Everything a {@link GmCommand} needs to do its work, plus a typed
 * argument accessor. Mirrors CMaNGOS's {@code ChatHandler} role: it
 * holds the invoking session and exposes parsed arguments and a reply
 * sink so commands never touch the wire directly.
 *
 * <p>The argument array is the whitespace-split tail of the chat line
 * (the command name itself already stripped). Accessors are
 * fail-soft: out-of-range / unparseable values surface as
 * {@link IllegalArgumentException} which the dispatcher turns into a
 * {@link CommandResult.Status#BAD_SYNTAX} reply.
 */
public final class CommandContext {

    private final Player player;
    private final String[] args;
    private final java.util.function.Consumer<String> replySink;

    public CommandContext(Player player, String[] args,
                          java.util.function.Consumer<String> replySink) {
        this.player = player;
        this.args = args == null ? new String[0] : args;
        this.replySink = replySink;
    }

    public Player player() {
        return player;
    }

    public PlayerCharacter character() {
        return player == null ? null : player.getCharacter();
    }

    public Account account() {
        return player == null ? null : player.getAccount();
    }

    /** GM level of the invoking account (0 if none attached). */
    public int callerGmLevel() {
        Account a = account();
        return a == null ? 0 : a.getGmLevel();
    }

    public int argCount() {
        return args.length;
    }

    public String[] rawArgs() {
        return args;
    }

    /** Raw string argument at {@code i}. */
    public String arg(int i) {
        if (i < 0 || i >= args.length) {
            throw new IllegalArgumentException(
                    "missing argument #" + (i + 1));
        }
        return args[i];
    }

    /** All args from index {@code i} joined back with single spaces. */
    public String argsFrom(int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException(
                    "missing argument #" + (i + 1));
        }
        StringBuilder sb = new StringBuilder();
        for (int k = i; k < args.length; k++) {
            if (k > i) sb.append(' ');
            sb.append(args[k]);
        }
        return sb.toString();
    }

    /** Decimal or 0x-hex integer at {@code i}. */
    public int intArg(int i) {
        String s = arg(i).trim();
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return (int) Long.parseLong(s.substring(2), 16);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "argument #" + (i + 1) + " '" + s
                            + "' is not an integer");
        }
    }

    /** Integer at {@code i} with a fallback when the arg is absent. */
    public int intArgOr(int i, int dflt) {
        return i < args.length ? intArg(i) : dflt;
    }

    public float floatArg(int i) {
        String s = arg(i).trim();
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "argument #" + (i + 1) + " '" + s
                            + "' is not a number");
        }
    }

    /** Send a line of feedback to the invoking player. */
    public void reply(String message) {
        if (replySink != null) replySink.accept(message);
    }
}
