package server.gameserver.packets.server_udp;

import java.net.DatagramPacket;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;
import server.networktools.PacketBuilderUDP1303;

/**
 * Per-NPC zone-state refresh emitted once per {@code ZoneStateHeartbeat}
 * tick. Produces THREE independent UDP datagrams, matching retail's
 * observed wire structure (RETAIL_PLAZA_CROSSZONE byte-diff 2026-05-16):
 *
 * <ul>
 *   <li>a raw {@code 0x1b} position broadcast — its own datagram,
 *       UNRELIABLE (no reliable seq consumed);</li>
 *   <li>a reliable {@code 0x03→0x2d} NPCData — its own
 *       {@link PacketBuilderUDP1303} datagram;</li>
 *   <li>a reliable {@code 0x03→0x28} WorldInfo — its own
 *       {@link PacketBuilderUDP1303} datagram.</li>
 * </ul>
 *
 * <p><strong>Why three datagrams, not one bundled frame.</strong> The
 * previous implementation packed all three sub-packets into a single
 * {@code 0x13} datagram and hand-rolled {@code [0x03][seq]} prefixes
 * for the two reliables via {@code incandgetSessionCounter()}. That
 * consumed two reliable sequence numbers per tick but buried them
 * behind a non-reliable {@code 0x1b} sub-packet in one frame. The
 * client's reliable layer could not track those buried seqs, so its
 * received-seq view jumped by 3–5 per tick instead of +1. It then
 * believed it had missed a swathe of reliables, flooded
 * {@code 0x01} retransmit-requests, and stayed in the
 * "Synchronizing" recovery loop — never advancing to Zoning2, which
 * is what blocked the plaza_p1 → plaza_p3 cross.
 *
 * <p>Splitting restores the retail invariant: every reliable seq is
 * exactly one {@code 0x13/0x03} datagram the client can account
 * contiguously. {@link PacketBuilderUDP1303} also records each into
 * the per-session retransmit ring automatically, so a genuine
 * retransmit request can be satisfied.
 */
public class ZoneStateCompoundPacket extends PacketBuilderUDP13 {

    private final Player owner;
    private final NPC npc;
    private final int npcId;

    /**
     * Byte-exact retail {@code 0x03/0x2d} NPC-tick sample
     * (sub-action 0xf4, category 0x0003), 55 bytes. Pinned from
     * 6 retail captures (task #167). Per-NPC fields are overlaid
     * at runtime: entity id at [7..8], five float32 at [15..34].
     */
    private static final byte[] NPC2D_TEMPLATE = hex(
        "2df4030000712085549b45ffffffff457ba93844d78a5645d063ac45"
      + "7be93044d78a564543000080060000000100000081ca0900709c53");

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(
                s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    /** Write {@code (float) v} little-endian at {@code off}. */
    private static void putFloatLE(byte[] buf, int off, int v) {
        int bits = Float.floatToIntBits((float) v);
        buf[off]     = (byte) (bits & 0xFF);
        buf[off + 1] = (byte) ((bits >> 8) & 0xFF);
        buf[off + 2] = (byte) ((bits >> 16) & 0xFF);
        buf[off + 3] = (byte) ((bits >> 24) & 0xFF);
    }

    /**
     * @param pl  the player receiving the datagrams
     * @param npc the NPC to broadcast state for
     */
    public ZoneStateCompoundPacket(Player pl, NPC npc) {
        super(pl);
        this.owner = pl;
        this.npc = npc;
        this.npcId = npc.getMapID();

        // Datagram 1: raw 0x1b position broadcast — written into the
        // parent PacketBuilderUDP13 (UNRELIABLE: no 0x03 wrapper, no
        // session-counter consumed).
        write(0x1b);                                  // [0] type
        write(npcId & 0xFF);                          // [1] id low
        write((npcId >> 8) & 0xFF);                   // [2] id high
        write(0x00);                                  // [3]
        write(0x00);                                  // [4]
        write(0x1f);                                  // [5] inner opcode

        int y = (npc.getYpos() + 32000) & 0xFFFF;
        int z = (npc.getZpos() + 32000) & 0xFFFF;
        int x = (npc.getXpos() + 32000) & 0xFFFF;

        writeShort(y);                                // [6-7]
        writeShort(z);                                // [8-9]
        writeShort(x);                                // [10-11]

        int angle = npc.getAngle() & 0xFF;
        write(angle == 0 ? 0x40 : angle);             // [12] orient
        // [13..14] entity_class_id LE16. RESOLVED 2026-05-17 (task
        // #178). The retail captures DO exist (ceres-j/strace/*.pcap)
        // and were decoded with tools/pcap-decode.py: this field is
        // 100% stable per entity (AUGUSTO ent 0x0124 = 1739 ×103, the
        // NCPD-guard group 0x0125–0x012f all = 29002) and NEVER
        // 0x0000 for a mobile NPC. Crucially the raw 0x1b for an
        // entity arrives BEFORE its reliable 0x28 WorldInfo (AUGUSTO
        // 0x0124: 0x1b @277.953 s, 0x28 @278.433 s), so the client
        // creates the world actor from THIS id on first sight —
        // emitting 0x0000 (the old behaviour) is exactly why NPCs
        // never rendered. The retail values (29002/29046/1739/…) are
        // server runtime spawn handles absent from EVERY client def
        // (npc.def maxes at 20008; 29002/29046 appear in no pak_*.def)
        // so there is no static type→id table to look up — same
        // category as the 0x28[7..10] instance handle. We emit the
        // NPC's real npc.def type id (genuine client data, stable,
        // non-zero); NPC.getEntityClassId() guarantees non-zero.
        int ecid = npc.getEntityClassId() & 0xFFFF;
        write(ecid & 0xFF);                           // [13] class lo
        write((ecid >> 8) & 0xFF);                    // [14] class hi
        write(0x00);                                  // [15] CONSTANT
        write(0x00);                                  // [16] CONSTANT
        write(0x11);                                  // [17] state hash lo
        write(0x11);                                  // [18] state hash hi
    }

    /**
     * Emit the three datagrams. The 0x1b broadcast comes from the
     * parent serializer (unreliable); NPCData and WorldInfo are built
     * here as standalone {@link PacketBuilderUDP1303} reliables so
     * each consumes exactly one contiguous reliable seq and is
     * recorded in the retransmit ring.
     */
    @Override
    public DatagramPacket[] getDatagramPackets() {
        DatagramPacket bcast = super.getDatagramPackets()[0];

        // Datagram 2: reliable 0x03→0x2d NPCData — the verified
        // retail 55-byte NPC-tick form (task #167/#177, byte-pinned
        // 2026-05-16 across 6 retail captures, see
        // docs/protocol/packets/udp_s2c_03_2d.md). The previous
        // 9-byte stub matched no retail sub-action, so the client's
        // world-actor/LSTPLAYER parser rejected it
        // ("Update Message corrupted" / "Unable to Spawn WA") and
        // NPCs never rendered.
        //
        // NPC2D_TEMPLATE is a byte-exact retail sample (sub-action
        // 0xf4, category 0x0003). Only the per-NPC fields are
        // overlaid: the 2-byte entity id at [7..8] and the five
        // float32 position/orient slots at [15..34]
        // (X, Y, Z, orient, Y-echo). Everything else — framing,
        // 0xffffffff sentinel, the invariant block and the tail —
        // is replicated verbatim from the retail packet.
        byte[] body = NPC2D_TEMPLATE.clone();
        body[7] = (byte) (npcId & 0xFF);
        body[8] = (byte) ((npcId >> 8) & 0xFF);
        putFloatLE(body, 15, npc.getXpos());
        putFloatLE(body, 19, npc.getYpos());
        putFloatLE(body, 23, npc.getZpos());
        putFloatLE(body, 27, npc.getAngle());
        putFloatLE(body, 31, npc.getYpos());

        PacketBuilderUDP1303 npcData = new PacketBuilderUDP1303(owner);
        npcData.write(body);
        DatagramPacket npcDp = npcData.getDatagramPackets()[0];

        // Datagram 3: reliable 0x03→0x28 WorldInfo. Body is built by
        // WorldNPCInfo.writeBody so the two 0x03/0x28 emitters share
        // one retail-evidenced layout and cannot drift apart again
        // (byte-diffed 2026-05-16 vs the verified retail decode in
        // docs/protocol/packets/udp_s2c_03_28.md + the preserved raw
        // samples in docs/protocol/_data/packets.json). The previous
        // hand-rolled block hard-coded the per-NPC [7..10] instance
        // handle to the constant 8958887 (absent from all retail
        // evidence) so every NPC collided onto one client world-object
        // reference, and mislocated the trailing strings by 1 byte.
        PacketBuilderUDP1303 world = new PacketBuilderUDP1303(owner);
        world.write(0x28);
        WorldNPCInfo.writeBody(world, npc);
        DatagramPacket worldDp = world.getDatagramPackets()[0];

        return new DatagramPacket[] { bcast, npcDp, worldDp };
    }
}
