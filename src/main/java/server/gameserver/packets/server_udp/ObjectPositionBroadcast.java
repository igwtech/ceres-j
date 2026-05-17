package server.gameserver.packets.server_udp;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Raw (unreliable) {@code 0x1b} object-position broadcast.
 *
 * <p>Wire format (19 bytes total) reverse-engineered from 122 samples in
 * retail capture ACC1_CHAR1 — constant-byte positions confirmed across
 * all captures:
 *
 * <pre>
 *   offset 0  : 0x1b              sub-packet type
 *   offset 1  : objectId byte     per-object rotating id (varies 0-255)
 *   offset 2  : 0x01              constant (object class marker)
 *   offset 3-4: 0x00 0x00         constant padding
 *   offset 5  : 0x1f              constant inner opcode
 *   offset 6-7: Y LE uint16       (Y + 32000), signed world coord
 *   offset 8-9: Z LE uint16       (Z + 32000)
 *   offset 10-11: X LE uint16     (X + 32000)
 *   offset 12 : orientation byte  (0x40 is default heading in samples)
 *   offset 13-14: status/flags    2 bytes
 *   offset 15-16: 0x00 0x00       constant padding
 *   offset 17-18: trailer         2 bytes (0x11 0x0f / 0x11 0x11 dominant)
 * </pre>
 *
 * <p><b>Why this exists:</b> retail servers broadcast 122 of these per
 * session (7.7 Hz) and without them the modern NCE 2.5 client's
 * world-alive watchdog expires after ~10-25 seconds of playing, the
 * "SYNCHRONIZING INTO CITY ZONE" overlay re-appears, and the session
 * times out. It's unreliable (no {@code 0x03} wrapper) so it does NOT
 * cause position rubberbanding the way a reliable
 * {@code 0x03→0x1b PlayerPositionUpdate} echo does. See
 * {@code docs/retail_burst_analysis.md} and the diff comparison in
 * memory {@code raw_1b_broadcast.md}.
 *
 * <p>This packet carries the moving player's own position but with a
 * synthetic {@code objectId} distinct from the player's map id, so the
 * client treats it as a generic zone object broadcast (the watchdog
 * input) rather than a self-position override.
 */
public class ObjectPositionBroadcast extends PacketBuilderUDP13 {

    /**
     * Build a raw 0x1b position broadcast for a specific NPC.
     *
     * <p>Retail sends 122 of these per session (7.7 Hz in ACC1_CHAR1).
     * Each one carries the position of a zone-resident object (NPC or
     * item). The NPC's {@code mapID} is split into low (offset 1) and
     * high (offset 2) bytes — the client reconstructs the 16-bit id
     * from both bytes.
     *
     * @param pl  the player receiving the broadcast
     * @param npc the NPC whose position to broadcast
     */
    public ObjectPositionBroadcast(Player pl, NPC npc) {
        super(pl);

        int id = npc.getMapID();

        write(0x1b);                                  // [0] type
        write(id & 0xFF);                             // [1] object id low byte
        write((id >> 8) & 0xFF);                      // [2] object id high byte
        write(0x00);                                  // [3]
        write(0x00);                                  // [4]
        write(0x1f);                                  // [5] inner opcode

        int y = (npc.getYpos() + 32000) & 0xFFFF;
        int z = (npc.getZpos() + 32000) & 0xFFFF;
        int x = (npc.getXpos() + 32000) & 0xFFFF;

        writeShort(y);                                // [6-7] Y
        writeShort(z);                                // [8-9] Z
        writeShort(x);                                // [10-11] X

        int angle = npc.getAngle() & 0xFF;
        write(angle == 0 ? 0x40 : angle);             // [12] orient

        // [13..14] entity_class_id LE16. Retail (AUGUSTO/NORMAN/
        // DRSTONE4, decoded 2026-05-17 via tools/pcap-decode.py): this
        // field is 100% stable per entity and NEVER 0x0000 for a
        // mobile NPC, and the raw 0x1b arrives BEFORE the reliable
        // 0x28 WorldInfo — so the client creates the world actor from
        // THIS id on first sight. Emitting 0x0000 here (the pre-fix
        // behaviour) meant the actor was never instantiated and the
        // NPC never rendered. The retail values are server runtime
        // handles absent from every client def, so we emit the NPC's
        // real npc.def type id (stable, non-zero, genuine client
        // data); NPC.getEntityClassId() guarantees non-zero.
        int ecid = npc.getEntityClassId() & 0xFFFF;
        writeShort(ecid);                             // [13-14] class id
        write(0x00);                                  // [15]
        write(0x00);                                  // [16]
        write(0x11);                                  // [17] trailer
        write(0x11);                                  // [18] trailer
    }
}
