package server.gameserver.packets.server_udp;

import java.net.DatagramPacket;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.interfaces.ServerUDPPacket;
import server.networktools.PacketBuilderUDP1303;

/**
 * Per-NPC zone-state refresh emitted once per {@code ZoneStateHeartbeat}
 * tick.
 *
 * <p><strong>Byte-pinned 2026-05-17 (task #178d)</strong> from the live
 * retail pcap {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}
 * (server 157.90.195.74), machine-decoded with the corrected reliable
 * frame parser ({@code tools/npc-lifecycle.py}: outer
 * {@code 13 [octr LE2] [ackkey LE2]} then repeating
 * {@code [sublen LE2][wrapper][seq LE2][op][data]} sub-packets).
 *
 * <h3>What retail actually sends for a persistently-rendered NPC</h3>
 *
 * For the EXACT scripted-city-NPC class the client renders and keeps
 * (entities 266 "WSK" / 299 "WCOP" / 325 "PATROL_COPBOT6"), the
 * complete per-NPC S→C lifecycle is <b>two</b> reliable sub-packets
 * only:
 *
 * <ul>
 *   <li>reliable {@code 0x03 -> 0x28} WorldInfo (the Type-15
 *       create/refresh) — body built by
 *       {@link WorldNPCInfo#writeBody}, re-sent ~every 6 s;</li>
 *   <li>reliable {@code 0x03 -> 0x2d} 6-byte NPC-data ping
 *       {@code 2d [entityId LE2] 0000 06}, ~1 s after each
 *       {@code 0x28} (see {@link NpcDataBroadcast}).</li>
 * </ul>
 *
 * <p><strong>Retail sends NO raw {@code 0x1b} for a scripted NPC.</strong>
 * The pcap decode is decisive: every raw {@code 0x1b} broadcast in the
 * whole capture carries a small id in {@code 0x8b..0xdb} (the
 * world-item / {@code RemoveWorldItem} object space), while every
 * scripted NPC id is {@code >= 0x0100} and appears ONLY in
 * {@code 0x28} / {@code 0x2d}. The two id spaces never overlap and no
 * {@code 0x28} is ever sent for a {@code 0x1b} id.
 *
 * <p>The pre-#178d implementation emitted a raw {@code 0x1b} per NPC
 * keyed on the NPC's world-object id. That registered the scripted NPC
 * in the client's <em>transient world-item</em> table (the table the
 * client garbage-collects and the one {@code 0x03/0x26
 * RemoveWorldItem} targets) instead of — or in addition to — the
 * {@code SCRIPTEDPLAYER} actor. The client then aged the world-item
 * entry out within a frame, which is exactly the task #178
 * "NPC appears animating then disappears" symptom: no server
 * {@code 0x26} is needed — the spurious {@code 0x1b} makes the client
 * remove its own just-created actor. Retail proves the fix is to send
 * {@code 0x28 + 0x2d} only and never a {@code 0x1b} for an NPC id.
 *
 * <p>The pre-#178d {@code 0x2d} was also wrong: it was a 55-byte
 * template (sub-action 0xf4, entity overlaid at {@code [7..8]}, five
 * float32 at {@code [15..34]}) from an older capture that does not
 * match this NPC class. The live decode shows the entity id is at
 * {@code [1..2]}, the byte at {@code [5]} is a form discriminator, and
 * a stationary NPC's tick is the deterministic 6-byte ping (the only
 * fully byte-reproducible {@code 0x2d} form — the 55-byte form's
 * {@code [7..10]} and {@code [15..54]} are opaque per-tick server
 * state with no derivation, the same category as the {@code 0x28}
 * instance handle, so emitting a fabricated 55-byte record would be
 * pure speculation). The 6-byte ping is what keeps an idle NPC alive
 * in retail.
 *
 * <h3>Emitted datagrams</h3>
 *
 * Two reliable {@link PacketBuilderUDP1303} datagrams, each consuming
 * exactly one contiguous reliable seq and recorded in the per-session
 * retransmit ring:
 * <ol>
 *   <li>reliable {@code 0x03 -> 0x28} WorldInfo;</li>
 *   <li>reliable {@code 0x03 -> 0x2d} 6-byte NPC-data ping.</li>
 * </ol>
 * No raw {@code 0x1b} datagram is produced (the previous third
 * datagram is removed).
 */
public class ZoneStateCompoundPacket implements ServerUDPPacket {

    private final Player owner;
    private final NPC npc;

    /**
     * @param pl  the player receiving the datagrams
     * @param npc the NPC to broadcast state for
     */
    public ZoneStateCompoundPacket(Player pl, NPC npc) {
        this.owner = pl;
        this.npc = npc;
    }

    /**
     * Emit the WorldInfo {@code 0x28} + NpcData ping {@code 0x2d} as
     * TWO sub-packets in ONE reliable {@code 0x13} datagram (each sub
     * still gets its own contiguous seq + retransmit-ring entry).
     *
     * <p><strong>2026-05-30 bundling fix.</strong> Pre-fix this method
     * built two separate {@link PacketBuilderUDP1303} instances and
     * returned two {@link DatagramPacket}s — i.e. two UDP datagrams
     * per NPC, each with its own LFSR seed + IP/UDP overhead. On
     * world-entry the per-zone loop emits one of these per NPC, so a
     * 5-NPC zone produced 10 distinct datagrams in rapid succession.
     * The live wire log 2026-05-30 (msn3wolf chair-sit post-cross)
     * showed the client NAK-storming (255-byte burst, 85+ NAKs)
     * during exactly this density, dropping the in-flight sit
     * packets and breaking #205/#232.
     *
     * <p>Bundling the two sub-packets into ONE datagram halves the
     * datagram count for the NPC dump and is what
     * {@link PacketBuilderUDP1303#newSubPacket()} was designed for —
     * each sub still consumes its own seq, but the wrapper +
     * encryption pass amortises across both.
     */
    @Override
    public DatagramPacket[] getDatagramPackets() {
        PacketBuilderUDP1303 b = new PacketBuilderUDP1303(owner);
        // Sub 1: reliable 0x03/0x28 WorldInfo. Body built by
        // WorldNPCInfo.writeBody so the two 0x03/0x28 emitters share
        // one retail-evidenced layout (#178c).
        b.write(0x28);
        WorldNPCInfo.writeBody(b, npc);
        // Sub 2: reliable 0x03/0x2d 6-byte NpcData ping
        // (2d [entityId LE2] 0000 06). Body extracted to
        // NpcDataBroadcast.writeBody so we don't double-construct
        // a builder (#178d byte-spec preserved).
        b.newSubPacket();
        NpcDataBroadcast.writeBody(b, npc);
        return b.getDatagramPackets();
    }
}
