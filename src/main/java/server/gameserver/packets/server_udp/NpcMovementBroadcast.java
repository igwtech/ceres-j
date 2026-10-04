package server.gameserver.packets.server_udp;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Raw {@code 0x20} mob movement broadcast (S→C), 13 bytes:
 * <pre>
 *   [0]      0x20
 *   [1..2]   entity id LE16        (== the mob's 0x28 [3..4])
 *   [3..8]   Y, Z, X LE16          world + 0x8000 (NPC.getWireX)
 *   [9]      heading byte          per-mob, varies as it turns
 *   [10]     flags                 0x00 / 0x02 seen
 *   [11..12] 00 00
 * </pre>
 *
 * <p>Pinned 2026-10-04 from harness run fire_bc_v1 (industry_p1 mobs,
 * 13-byte form only): e.g. SSCORPA 1016 {@code 20 f803 137a ac7f ca79
 * 7c 000000} — Y/Z 0x7a13/0x7fac against its 0x28 Y/Z 0x7a10/0x7fab;
 * BIGMUT 973 {@code 20 cd03 367d 007f 9f7e f0 020000}. Static city
 * NPCs get no {@code 0x20}; the 17/29-byte {@code 0x20 .. 27 + 3×f32}
 * form is the other-player broadcast ({@code SMovement}). Not wired
 * yet: Ceres-J has no dynamic mob spawner.
 */
public class NpcMovementBroadcast extends PacketBuilderUDP13 {

    public NpcMovementBroadcast(Player pl, NPC npc) {
        super(pl);

        write(0x20);
        writeShort(npc.getMapID());
        writeShort(npc.getWireY());
        writeShort(npc.getWireZ());
        writeShort(npc.getWireX());
        write(npc.getAngle() & 0xFF);  // heading byte (encoding not pinned)
        write(0x00);                   // flags
        write(0x00);
        write(0x00);
    }
}
