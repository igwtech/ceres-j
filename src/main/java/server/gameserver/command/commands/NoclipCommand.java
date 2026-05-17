package server.gameserver.command.commands;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import server.database.accounts.Account;
import server.gameserver.Player;
import server.gameserver.command.CommandContext;
import server.gameserver.command.CommandResult;
import server.gameserver.command.GmCommand;

/**
 * {@code !noclip} — toggle the no-collision / free-flight flag on the
 * invoking player.
 *
 * <p><b>Status: framework-complete, wire-effect stubbed.</b> The
 * server-side toggle and its query API are fully implemented and
 * tested ({@link #isNoclip(Player)}). What is <em>not</em> wired is the
 * client-visible collision bypass: NC2 has no confirmed runtime
 * "disable collision" sub-opcode (movement authority is client-side;
 * see project memory {@code synchronizing_overlay_root_cause} and
 * {@code lstplayer_error_misattribution}). Emitting a speculative
 * packet here risks the SYNCHRONIZING overlay hang. The flag is kept
 * authoritative server-side so the movement validator can stop
 * rejecting out-of-bounds positions for noclip players once that path
 * is reverse-engineered.
 *
 * <p>TODO(#179): once the retail noclip/fly sub-opcode is captured,
 * emit it from {@link #execute} and have the movement validator honour
 * {@link #isNoclip(Player)}.
 */
public final class NoclipCommand implements GmCommand {

    /** Per-player noclip state. WeakHashMap so logged-out players GC. */
    private static final Map<Player, Boolean> STATE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** True if noclip is currently enabled for {@code pl}. */
    public static boolean isNoclip(Player pl) {
        return pl != null && Boolean.TRUE.equals(STATE.get(pl));
    }

    /** Test/utility reset. */
    public static void clear(Player pl) {
        STATE.remove(pl);
    }

    @Override
    public String name() {
        return "noclip";
    }

    @Override
    public String[] aliases() {
        return new String[]{"fly"};
    }

    @Override
    public int requiredGmLevel() {
        return Account.GM_GAMEMASTER;
    }

    @Override
    public String usage() {
        return "(toggle collision/flight on yourself)";
    }

    @Override
    public String description() {
        return "Toggle no-collision/flight mode on yourself.";
    }

    @Override
    public CommandResult execute(CommandContext ctx) {
        Player pl = ctx.player();
        if (pl == null) {
            return CommandResult.error("no player attached");
        }
        boolean now = !isNoclip(pl);
        STATE.put(pl, now);
        // Wire effect intentionally not emitted — see class javadoc.
        return CommandResult.ok("Noclip "
                + (now ? "ENABLED" : "disabled")
                + " (server-side flag; client collision bypass pending"
                + " protocol RE — TODO #179).");
    }
}
