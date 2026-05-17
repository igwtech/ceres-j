package server.gameserver.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import server.database.accounts.Account;
import server.gameserver.Player;

/**
 * Central registry + dispatcher for {@link GmCommand}s — the
 * CMaNGOS {@code CommandTable} equivalent.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Hold the command table keyed by name and alias.</li>
 *   <li>Parse a chat line into {@code (name, args[])}.</li>
 *   <li>Enforce the per-command GM-level gate against the caller's
 *       {@link Account#getGmLevel()} <em>before</em> the command body
 *       runs.</li>
 *   <li>Translate accessor {@link IllegalArgumentException}s into a
 *       BAD_SYNTAX result so command authors never write boilerplate
 *       arg validation.</li>
 * </ul>
 *
 * <p>The default registry is populated once via
 * {@link #defaultRegistry()} with the task-#179 command set. Tests can
 * build their own empty registry for isolation.
 */
public final class GmCommandRegistry {

    private final Map<String, GmCommand> table = new LinkedHashMap<>();
    private final List<GmCommand> commands = new ArrayList<>();

    /** Register a command under its name and every alias. */
    public synchronized GmCommandRegistry register(GmCommand cmd) {
        if (cmd == null) throw new IllegalArgumentException("null command");
        String name = cmd.name().toLowerCase();
        if (table.containsKey(name)) {
            throw new IllegalStateException(
                    "duplicate command name: " + name);
        }
        commands.add(cmd);
        table.put(name, cmd);
        for (String a : cmd.aliases()) {
            String al = a.toLowerCase();
            if (!table.containsKey(al)) {
                table.put(al, cmd);
            }
        }
        return this;
    }

    /** Lookup by name or alias (case-insensitive); null if absent. */
    public synchronized GmCommand lookup(String name) {
        return name == null ? null : table.get(name.toLowerCase());
    }

    /** All distinct registered commands, registration order. */
    public synchronized List<GmCommand> commands() {
        return Collections.unmodifiableList(new ArrayList<>(commands));
    }

    public synchronized boolean isRegistered(String name) {
        return name != null && table.containsKey(name.toLowerCase());
    }

    /**
     * Split a chat line (already stripped of the {@code .}/{@code !}
     * command prefix) into
     * the command keyword (index 0) and the whitespace-separated
     * argument tokens (rest). Returns an empty array for a blank line.
     */
    public static String[] tokenize(String line) {
        if (line == null) return new String[0];
        String trimmed = line.replace("\0", "").trim();
        if (trimmed.isEmpty()) return new String[0];
        return trimmed.split("\\s+");
    }

    /**
     * Parse + permission-gate + execute. Never throws for user error.
     *
     * @param player    invoking session (may carry the Account whose
     *                   GM level is checked)
     * @param line      chat text with the leading {@code !} removed
     * @param replySink where command feedback lines are delivered
     */
    public CommandResult dispatch(Player player, String line,
                                  java.util.function.Consumer<String> replySink) {
        String[] tokens = tokenize(line);
        if (tokens.length == 0) {
            return CommandResult.notFound("empty command");
        }
        String name = tokens[0].toLowerCase();
        GmCommand cmd = lookup(name);
        if (cmd == null) {
            return CommandResult.notFound(
                    "Unknown command: !" + name + ". Type !help.");
        }

        int callerLevel = gmLevelOf(player);
        if (callerLevel < cmd.requiredGmLevel()) {
            return CommandResult.denied(
                    "You lack the GM level for !" + cmd.name()
                            + " (need " + cmd.requiredGmLevel()
                            + ", have " + callerLevel + ").");
        }

        String[] args = new String[tokens.length - 1];
        System.arraycopy(tokens, 1, args, 0, args.length);
        CommandContext ctx = new CommandContext(player, args, replySink);
        try {
            CommandResult r = cmd.execute(ctx);
            return r == null ? CommandResult.ok("") : r;
        } catch (IllegalArgumentException badArg) {
            return CommandResult.badSyntax(
                    "Usage: !" + cmd.name() + " " + cmd.usage()
                            + "  (" + badArg.getMessage() + ")");
        } catch (RuntimeException unexpected) {
            return CommandResult.error(
                    "!" + cmd.name() + " failed: " + unexpected);
        }
    }

    private static int gmLevelOf(Player player) {
        if (player == null) return 0;
        Account a = player.getAccount();
        return a == null ? 0 : a.getGmLevel();
    }

    // ── Default registry (task #179 command set) ──────────────────────

    private static volatile GmCommandRegistry DEFAULT;

    /**
     * The process-wide registry the chat layer routes to. Lazily built
     * with every task-#179 command. Thread-safe double-checked init.
     */
    public static GmCommandRegistry defaultRegistry() {
        GmCommandRegistry r = DEFAULT;
        if (r == null) {
            synchronized (GmCommandRegistry.class) {
                r = DEFAULT;
                if (r == null) {
                    r = new GmCommandRegistry();
                    server.gameserver.command.commands.CommandPack
                            .registerAll(r);
                    DEFAULT = r;
                }
            }
        }
        return r;
    }
}
