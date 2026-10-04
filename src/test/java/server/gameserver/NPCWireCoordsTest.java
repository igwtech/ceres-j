package server.gameserver;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins {@link NPC#getWireX()} / {@code getWireY()} / {@code getWireZ()}
 * to retail bytes. Retail encodes an NPC position in 0x03/0x28 and raw
 * 0x1b as LE16 world + 0x8000, Z one below the world-.dat value.
 */
public class NPCWireCoordsTest {

    @Test
    public void plazaP1WskMatchesRetailRaw1b() {
        // world_npcs plaza_p1 npc 11 (type 2890 "WSK", angle "-178"):
        // pos (2672.2234, -1887.4149, -239). Retail entity 266
        // (= 0xFF + 11) raw 0x1b: 1b 0a01 0000 1f a078 107f 708a ...
        NPC npc = new NPC(0xFF + 11, 1, 2890, "WSK",
                (int) Math.floor(2672.2234f),
                (int) Math.floor(-1887.4149f),
                (int) Math.floor(-239f), -178, 100, 0);
        assertEquals(266, npc.getMapID());
        assertEquals(0x78a0, npc.getWireY());
        assertEquals(0x7f10, npc.getWireZ());
        assertEquals(0x8a70, npc.getWireX());
    }

    @Test
    public void pepperP1NpcsMatchRetail0x28() {
        // world_npcs pepper_p1 -> retail 0x03/0x28 [13..18] (Y, Z, X).
        // npc 5 (135.3573, -550.8545, -911) -> 260: Y -551 Z -912 X 135
        NPC a = new NPC(0xFF + 5, 5, 10513, "WSK",
                (int) Math.floor(135.3573f), (int) Math.floor(-550.8545f),
                (int) Math.floor(-911f), 0, 100, 0);
        assertEquals(260, a.getMapID());
        assertEquals(-551 + 0x8000, a.getWireY());
        assertEquals(-912 + 0x8000, a.getWireZ());
        assertEquals(135 + 0x8000, a.getWireX());
        // npc 38 (352.558, -496.6854, -703) -> 293: Y -497 Z -704 X 352
        NPC b = new NPC(0xFF + 38, 5, 284, "WSK",
                (int) Math.floor(352.558f), (int) Math.floor(-496.6854f),
                (int) Math.floor(-703f), -90, 100, 0);
        assertEquals(293, b.getMapID());
        assertEquals(-497 + 0x8000, b.getWireY());
        assertEquals(-704 + 0x8000, b.getWireZ());
        assertEquals(352 + 0x8000, b.getWireX());
    }

    @Test
    public void spiderCaveNestNoLongerFarOutOfZone() {
        // spider_cave_1 SPIDERNEST2 npc 2 at (1405.43, -126.56, 1). The
        // pre-fix writer sent raw two's-complement (X=0x057d, Y=0xff82,
        // Z=0x0001), which the client decodes ~31,000 units away.
        NPC n = new NPC(0xFF + 2, 8000, 10601, "SPIDERNEST2",
                1405, (int) Math.floor(-126.56233f), 1, 0, 100, 0);
        assertEquals(1405 + 0x8000, n.getWireX());
        assertEquals(-127 + 0x8000, n.getWireY());
        assertEquals(0x8000, n.getWireZ());
    }
}
