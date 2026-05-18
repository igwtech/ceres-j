package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.tools.Out;

/**
 * Client-&gt;server "instant suicide" request — the wire form of the
 * documented retail command {@code /set kill_self 1}
 * (wiki.techhaven.org/Set_Commands: <i>"Instant suicide."</i>).
 *
 * <h3>How the retail client produces this</h3>
 *
 * <p>Typing {@code /set kill_self 1} does NOT send the string. The
 * client's {@code /set} config dispatcher
 * ({@code FUN_0065d710} in {@code neocronclient.exe}) matches the
 * {@code kill_self} keyword and branches on the local player-object
 * flag at {@code +0x29}:
 *
 * <ul>
 *   <li><b>flag == 0</b> (the common in-world case): the client calls
 *       its own player-object death vtable slot
 *       ({@code (+0x84)(0)}) — a <i>local</i> death request. The
 *       client then dies through the normal damage/pool path the
 *       server already drives.</li>
 *   <li><b>flag != 0</b> (vehicle / special-state case): the client
 *       emits an app-action packet via
 *       {@code FUN_006576f0(payload, 4)} where the payload is
 *       {@code 10 00 00 00}. That helper frames it as
 *       {@code [0x15][localId&0x3ff LE16][0x3d][payload]}, which the
 *       Winsock wrapper renumbers onto the standard
 *       {@code 0x03/0x1f/&lt;localId&gt;/0x3d} app-action channel
 *       (same channel/tag the ~90 Hz in-flight heartbeat sub-byte
 *       {@code 0x11} rides on).</li>
 * </ul>
 *
 * <h3>Wire format</h3>
 *
 * <pre>
 *   1f &lt;localId LE16&gt; 3d 10 00 00 00
 *   ^^ sub-opcode       ^^ ^^ app-action sub-byte 0x10 = kill_self
 *                       tag
 * </pre>
 *
 * <p><b>Capture provenance.</b> This byte form is derived from a
 * static decompile of {@code FUN_0065d710} /
 * {@code FUN_006576f0}, NOT from a live capture: the available
 * retail pcap {@code RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}
 * contains the captured player sitting and talking to an NPC only —
 * it never executes {@code kill_self}, so the only {@code 0x1f/0x3d}
 * app-action sub-bytes observed there are {@code 0x11} (heartbeat,
 * 2684×) and {@code 0x32} (status snapshot, 12×). The {@code 0x10}
 * sub-byte is binary-pinned and capture-gated until a pcap of an
 * actual suicide is taken.
 *
 * <p>The server reaction is the documented "instant suicide":
 * {@link Player#die()} (lethal self-damage → respawn flow).
 */
public final class KillSelfRequest extends GamePacketDecoderUDP {

    /** App-action sub-byte that identifies a suicide request. */
    public static final int APP_ACTION_KILL_SELF = 0x10;

    public KillSelfRequest(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || pl.getCharacter() == null) return;
        Out.writeln(Out.Info,
                "KillSelfRequest: /set kill_self from "
                        + pl.getCharacter().getName()
                        + " — applying lethal self-damage.");
        pl.die();
    }
}
