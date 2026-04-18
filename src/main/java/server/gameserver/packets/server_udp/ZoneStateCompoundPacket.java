package server.gameserver.packets.server_udp;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Compound {@code 0x13} datagram packing multiple zone-state sub-packets
 * into a single UDP frame, matching retail's observed wire structure.
 *
 * <p>Retail sends compound datagrams containing a mix of:
 * <ul>
 *   <li>Raw {@code 0x1b} position broadcasts (19 B each)</li>
 *   <li>Reliable {@code 0x03→0x2d} NPCData (13 B each)</li>
 *   <li>Reliable {@code 0x03→0x28} WorldInfo (46–50 B each)</li>
 * </ul>
 *
 * <p>Our earlier approach of sending each type as a separate datagram
 * failed in two ways: (1) the raw 0x1b bytes were misframed — the
 * client parsed byte 0 of the sub-packet as 0x00 instead of 0x1b, and
 * (2) sending 3 separate datagrams at 12 Hz combined flooded the
 * client's reliable queue and starved its movement loop.
 *
 * <p>This packet uses {@link PacketBuilderUDP13#newSubPacket()} to
 * append all three sub-types into one datagram per tick. The 0x1b
 * raw sub-packet's first byte IS 0x1b because it's just another
 * sub-packet inside the 0x13 container.
 */
public class ZoneStateCompoundPacket extends PacketBuilderUDP13 {

    /**
     * Build a compound datagram with one 0x1b broadcast + one 0x2d
     * NPCData + one 0x28 WorldInfo for the given NPC.
     *
     * @param pl  the player receiving the datagram
     * @param npc the NPC to broadcast state for
     */
    public ZoneStateCompoundPacket(Player pl, NPC npc) {
        super(pl);

        int npcId = npc.getMapID();

        // ── Sub-packet 1: raw 0x1b position broadcast (19 bytes) ──
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
        write(0x00);                                  // [13]
        write(0x00);                                  // [14]
        write(0x00);                                  // [15]
        write(0x00);                                  // [16]
        write(0x11);                                  // [17] trailer
        write(0x11);                                  // [18] trailer

        // ── Sub-packet 2: reliable 0x03→0x2d NPCData (13 bytes) ──
        newSubPacket();
        write(0x03);                                  // reliable wrapper
        writeShort(pl.getUdpConnection().incandgetSessionCounter());
        write(0x2d);                                  // NPCData sub-type
        writeShort(npcId);                            // NPC world-object id
        write(0x00);                                  // padding
        write(0x08);                                  // sub-block marker
        write(0x00);
        write(0x00);
        write(0x00);
        write(0x00);

        // ── Sub-packet 3: reliable 0x03→0x28 WorldInfo ──
        // Reuse WorldNPCInfo's structure but inline it here to avoid
        // creating a separate PacketBuilderUDP1303 instance.
        newSubPacket();
        write(0x03);                                  // reliable wrapper
        writeShort(pl.getUdpConnection().incandgetSessionCounter());
        write(0x28);                                  // WorldInfo sub-type
        write(0x00);
        write(0x01);                                  // unknown
        writeShort(npcId);                            // MapID
        write(0x00);
        write(0x00);
        writeInt(8958887);                            // fixed constant (from WorldNPCInfo)
        writeShort(npc.getType());                    // NPC type
        writeShort(npc.getYpos());                    // Y-Pos
        writeShort(npc.getZpos());                    // Z-Pos
        writeShort(npc.getXpos());                    // X-Pos
        write(0x00);                                  // Status
        writeShort(npc.getHP());                      // HP
        write(0x00);
        writeShort(2828);                             // fixed constant
        write(0x07);                                  // fixed constant
        writeShort(2313);                             // fixed constant
        // NPC name (null-terminated, padded to 16 bytes like WorldNPCInfo)
        byte[] nameBytes = npc.getName().getBytes();
        int nameLen = Math.min(nameBytes.length, 15);
        write(0x05); write(0x00); write(0x00); write(0x00);
        write(0x00); write(0x00);
        for (int i = 0; i < nameLen; i++) {
            write(nameBytes[i]);
        }
        write(0x00); // null terminator
        // Pad to align with WorldNPCInfo's trailing structure
        write(new byte[]{0x31, 0x38, 0x30, 0x00});
    }
}
