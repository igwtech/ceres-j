package server.gameserver.command.commands;

import server.database.accounts.Account;
import server.gameserver.Player;
import server.gameserver.command.CommandContext;
import server.gameserver.command.CommandResult;
import server.gameserver.command.GmCommand;
import server.gameserver.packets.server_udp.QuickCommand;
import server.gameserver.packets.server_udp.SetGamedata;
import server.gameserver.packets.server_udp.UpdateModel;

/**
 * {@code !noclip} — toggle the no-collision / free-flight flag on the
 * invoking player and push the client-side effect.
 *
 * <p>This command is reachable two ways, both gated by the same GM
 * level ({@link Account#GM_GAMEMASTER}):
 * <ul>
 *   <li>chat: {@code !noclip} / {@code !fly} (toggles)</li>
 *   <li>native client built-in: typing {@code /gm_noclip} in the real
 *       client, which arrives as a UDP sub-packet on the
 *       {@code 0x03/0x1f/<localId>/0x06} path and is decoded by
 *       {@code AdminCommandRequest}. That path passes an explicit
 *       desired value (enabled = byte != 0) via
 *       {@link #applyTo(Player, Boolean)}.</li>
 * </ul>
 *
 * <p>The wire effect (verified recipe from a retail GM-build trace):
 * <ol>
 *   <li>{@link Player#setNoclip(boolean)} — authoritative server flag
 *       so the movement validator stops rejecting out-of-bounds
 *       positions for this session.</li>
 *   <li>{@link UpdateModel} — re-streams the model so other clients
 *       render the GM with the Flying state.</li>
 *   <li>{@link QuickCommand}{@code (pl, 0x11, enabled?1:0)} — toggles
 *       client-local physics / collision.</li>
 *   <li>{@link SetGamedata}{@code (pl,"gm_noclip", enabled?1f:0f)} —
 *       sets the named gamedata var the client's Lua layer reads.</li>
 * </ol>
 */
public final class NoclipCommand implements GmCommand {

    @Override
    public String name() {
        return "noclip";
    }

    @Override
    public String[] aliases() {
        return new String[]{"fly", "gm_noclip"};
    }

    @Override
    public int requiredGmLevel() {
        return Account.GM_GAMEMASTER;
    }

    @Override
    public String usage() {
        return "[on|off]  (no arg = toggle)";
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
        Boolean desired = null;
        if (ctx.argCount() >= 1) {
            String a = ctx.arg(0).toLowerCase();
            if (a.equals("on") || a.equals("1") || a.equals("true")) {
                desired = Boolean.TRUE;
            } else if (a.equals("off") || a.equals("0")
                    || a.equals("false")) {
                desired = Boolean.FALSE;
            } else {
                return CommandResult.badSyntax(
                        "expected on|off or no argument");
            }
        }
        boolean now = applyTo(pl, desired);
        return CommandResult.ok("Noclip "
                + (now ? "ENABLED" : "disabled") + ".");
    }

    /**
     * Apply the noclip toggle and emit the full client wire recipe.
     * Shared by the chat path and the native {@code 0x1f/0x06} GM
     * packet path.
     *
     * @param pl      target session (must be non-null)
     * @param desired explicit target state, or {@code null} to flip the
     *                current state
     * @return the resulting noclip state
     */
    public static boolean applyTo(Player pl, Boolean desired) {
        boolean enabled = desired != null ? desired : !pl.isNoclip();
        pl.setNoclip(enabled);
        // 1. authoritative flag already set above.
        // 2. re-stream model so peers see the Flying state.
        if (pl.getCharacter() != null) {
            pl.send(new UpdateModel(pl));
        }
        // 3. toggle client-local physics / collision.
        pl.send(new QuickCommand(pl, QuickCommand.SUB_LOCAL_PHYSICS,
                enabled ? 1 : 0));
        // 4. set the named gamedata var the client's Lua layer reads.
        pl.send(new SetGamedata(pl, "gm_noclip", enabled ? 1f : 0f));
        return enabled;
    }
}
