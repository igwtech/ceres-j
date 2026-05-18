package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * First-time chair-sit confirmation echo — the per-actor
 * {@code 0x03/0x1f} sub-action {@code 0x17} that the retail server
 * sends back to the <em>acting player only</em> the first time it
 * sits on a given object. This is the packet that actually
 * transitions the local player into the seated state / plays the
 * sit animation; without it the client never visibly sits.
 *
 * <h3>Wire format (retail pcap-pinned)</h3>
 *
 * <p>Byte-pinned from
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (server
 * 157.90.195.74), decoded with {@code tools/pcap-decode.py}
 * (RE spec {@code docs/protocol/RE_tcp_confirm.md} §3.2(a), §3.4):
 *
 * <pre>
 *   t=199.386941  C→S  reliable 03 &lt;seq2&gt; 1f 03 00 17 00 c8 0c 00
 *   t=199.833702  S→C  reliable 03 &lt;seq2&gt; 1f 03 00 17 00 c8 0c 00
 *                                          ^^^^^^^^^^^^^^^^^^^^^^^^
 *                                          byte-identical echo
 * </pre>
 *
 * <p>Inner body (post {@code 03 seq2}):
 * {@code 1f <localId LE2> 17 <rawObjectId LE32>} — 8 bytes,
 * <strong>no seatId byte</strong> (unlike the {@code 0x21}
 * {@link SitOnChair} broadcast which appends a seatId). The echo is
 * byte-for-byte identical to the {@code 0x03/0x1f/0x17} UseItem
 * request the client sent (RE spec §3.1 C→S form
 * {@code 1f [subjectLocalId LE16] 17 [rawObjectId LE16…] 00}, which
 * Ceres' {@code UseItem} decodes as a LE32 {@code id}).
 *
 * <p>Client path (RE spec §3.3, Ghidra): the S→C {@code 0x03/0x1f}
 * rides the {@code 0x13} reliable splitter → WWORLDMGR
 * {@code FUN_00541f20} Type {@code 0x17} → {@code FUN_0064ec90}
 * (PlayerAction dispatcher) <strong>{@code case 0x17}</strong> →
 * {@code FUN_007a4890} — the call that seats the <em>local</em>
 * player. The {@code 0x21} broadcast routes to
 * {@code case 0x21}→{@code FUN_00662c00} (the <em>observed</em>
 * posture applier), which only updates peers' view of the sitter
 * and never seats the actor itself. Retail sends this {@code 0x17}
 * echo once per new object, then {@code 0x21} for refresh /
 * observers.
 *
 * <p>{@link PacketBuilderUDP1303} writes the
 * {@code [0x13][ctr][ctr+sk][len][0x03][seq LE2]} reliable frame, so
 * this builder writes only the body that follows the {@code 0x03 seq}
 * pair, starting at {@code 0x1f}.
 *
 * @see SitOnChair
 * @see server.gameserver.packets.client_udp.UseItem
 */
public class SitConfirm extends PacketBuilderUDP1303 {

    /**
     * @param pl          the acting player the reliable frame is sent
     *                    to (supplies the per-session seq counter);
     *                    this echo goes ONLY to the acting player.
     * @param subjectMapId map/local id of the player who sat down
     *                     (echoes the {@code localId} the client put
     *                     in its {@code 0x17} use packet =
     *                     {@code pl.getMapID()}).
     * @param rawObjectId the chair's rawObjectId — the exact LE32
     *                    value the client sent in its {@code 0x17}
     *                    use packet (echoed back unchanged).
     */
    public SitConfirm(Player pl, int subjectMapId, int rawObjectId) {
        super(pl);
        write(0x1f);
        writeShort(subjectMapId);   // localId LE16 (subject player)
        write(0x17);
        writeInt(rawObjectId);      // rawObjectId u32 LE (echoed)
    }
}
