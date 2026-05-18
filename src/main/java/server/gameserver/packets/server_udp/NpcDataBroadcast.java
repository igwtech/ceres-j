package server.gameserver.packets.server_udp;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Reliable {@code 0x13 -> 0x03 -> 0x2d} NPC-data tick (the 6-byte
 * "still-present" ping).
 *
 * <p><strong>Byte-pinned 2026-05-17 (task #178d)</strong> from the
 * live retail pcap
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (server
 * 157.90.195.74), machine-decoded with {@code tools/npc-lifecycle.py}.
 *
 * <p>The decode of the EXACT scripted-city-NPC class the client
 * renders persistently (entities 266 "WSK" / 299 "WCOP" /
 * 325 "PATROL_COPBOT6") shows the 0x2d NPC tick for a stationary
 * NPC is a <b>6-byte</b> body:
 *
 * <pre>
 *   [0]      2d                 sub-opcode
 *   [1-2]    entity_id LE16     == the 0x28 WorldInfo world-object id
 *   [3-4]    00 00              CONSTANT
 *   [5]      06                 form/length discriminator
 * </pre>
 *
 * <p>e.g. retail entity 266 (0x010a): {@code 2d 0a01 0000 06}, sent
 * repeatedly (~1 s after every 0x28 refresh) to keep the client's
 * world-actor table from aging the NPC out. The entity id is at
 * {@code [1..2]} — the SAME id the client created the actor from in
 * the {@code 0x03/0x28} WorldInfo. This is the form
 * {@link server.gameserver.npc.Npc2dRecordDecoder#LEN_PING}
 * (6-byte ping) already documents.
 *
 * <p><strong>What this corrected vs the pre-#178d implementation.</strong>
 * The old emitter wrote a 10-byte body
 * {@code [id LE2][00][08][00 00 00 00]} — that is the layout of the
 * <em>player-self</em> 0x2d (retail self tick {@code 2d 00 f80900 08
 * 2f78c545}), NOT an NPC tick. Sent for an NPC it placed the entity id
 * where the client expects the self-actor handle, so the client's
 * LSTPLAYER / world-actor parser framed the record wrong and logged
 * {@code LSTPLAYER : Update Message corrupted Size:19 31} /
 * {@code Size:12 32} and {@code @WWORLDMGR : Unable to Spawn WA}, then
 * dropped the just-created actor (the "appears then disappears"
 * symptom). The retail 6-byte ping is byte-deterministic and carries
 * no opaque/unreproducible fields, so it can be emitted byte-identical
 * to retail with no speculation.
 */
public class NpcDataBroadcast extends PacketBuilderUDP1303 {

    public NpcDataBroadcast(Player pl, NPC npc) {
        super(pl);
        write(0x2d);                          // [0] sub-opcode
        writeShort(npc.getMapID());           // [1-2] entity id LE16
        write(0x00);                          // [3] CONSTANT
        write(0x00);                          // [4] CONSTANT
        write(0x06);                          // [5] form discriminator
    }
}
