package server.gameserver.command.commands;

import server.database.accounts.Account;
import server.gameserver.Player;
import server.gameserver.command.CommandContext;
import server.gameserver.command.CommandResult;
import server.gameserver.command.GmCommand;
import server.gameserver.packets.server_udp.SetGamedata;

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
 * <h3>Wire recipe — what is and isn't retail-backed (task #182 audit)</h3>
 *
 * <p>The recipe was inherited from unverified collaborator code that
 * sent four things. Each was checked against retail evidence and the
 * unprovable pieces were removed rather than guessed:
 *
 * <ul>
 *   <li><b>KEPT — {@link Player#setNoclip(boolean)}:</b> authoritative
 *       server-side flag. {@link server.gameserver.packets.client_udp.Movement}
 *       now reads it (see {@code Movement.execute}); this is the lever
 *       that makes the server accept the free-flight positions instead
 *       of treating them as an anomaly.</li>
 *   <li><b>KEPT — {@link SetGamedata}{@code (pl,"gm_noclip", 1f/0f)}:</b>
 *       retail-backed. The string {@code "gm_noclip"} is hard-compiled
 *       into {@code neocronclient.exe} and read back as a float gated
 *       on {@code 0.0 < value} (decompiled at
 *       {@code docs/death_handler.txt:8643} /
 *       {@code docs/event_a8_refs.txt:27501} via the gamedata float
 *       accessor {@code FUN_004a4430("gm_noclip")}). This is the wire
 *       signal the client itself consumes for the flight state.</li>
 *   <li><b>REMOVED — {@code QuickCommand(pl, 0x11)} on
 *       {@code 0x03/0x1f/0x3d}:</b> NOT retail-backed. {@code 0x1f/0x3d}
 *       is the verified fall-damage / physics-heartbeat channel
 *       (memory: {@code cash_and_falldamage_subops.md}; PROTOCOL.md
 *       lists 0x3d generically as "Quick command" with no noclip
 *       semantics). "Sub-tag 0x11 = local-collision toggle" is an
 *       unverified hypothesis — no capture shows the retail server
 *       sending it for noclip — so emitting it would be inventing
 *       bytes.</li>
 *   <li><b>REMOVED — {@code UpdateModel}:</b> a full appearance
 *       re-stream is unrelated to flight and has no retail evidence
 *       tying it to noclip; it was wire noise.</li>
 * </ul>
 *
 * <p><b>Honest limitation:</b> no retail GM-build packet capture of a
 * {@code /gm_noclip} toggle exists in the project corpus, so the
 * exact S→C frame retail's GM server emits cannot be byte-pinned. The
 * {@code gm_noclip} gamedata float is the one piece provable from the
 * client binary itself, so that (plus the authoritative server flag)
 * is what we emit; nothing is fabricated.</p>
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
        // 1. authoritative server flag — Movement.execute() honours
        //    this so the server accepts free-flight positions.
        pl.setNoclip(enabled);
        // 2. the one retail-provable wire signal: the gamedata float
        //    the client binary itself reads (gated on 0.0 < value).
        pl.send(new SetGamedata(pl, "gm_noclip", enabled ? 1f : 0f));
        return enabled;
    }
}
