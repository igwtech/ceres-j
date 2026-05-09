package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.Zone;

/**
 * Sanity tests for {@link PacketTestFixture#newPlayerWithZoneAndNpcs(int)}.
 *
 * <p>The fixture method was added 2026-05-09 to plumb deterministic
 * NPC state into the test environment so the pcap-replay harness
 * can emit per-NPC traffic (WorldNPCInfo, NpcDataBroadcast,
 * ObjectPositionBroadcast). Previously the harness's S→C alignment
 * broke at every step where retail emitted NPC traffic but Ceres-J
 * iterated an empty NPC list.
 *
 * <p>Pin: each fixture-created NPC has a deterministic mapID
 * (0x0101+i), position (1000+i*10, 2000+i*10, 3000+i*10), type=42,
 * scriptName="WSK", modelName="wsk.mdl", angle=0, hp=100, armor=10.
 */
public class PacketTestFixtureTest {

    @Test
    public void newPlayerWithZoneAndNpcs_populatesExactlyN() {
        for (int n : new int[]{0, 1, 3, 10}) {
            Player pl =
                    PacketTestFixture.newPlayerWithZoneAndNpcs(n);
            Zone z = pl.getZone();
            assertNotNull("zone must be installed", z);
            List<NPC> npcs = z.getAllNPCs();
            assertEquals("zone must have exactly " + n + " NPCs",
                    n, npcs.size());
        }
    }

    @Test
    public void npcsHaveDeterministicMapIds() {
        // mapID i = 0x0101 + i. Pin to catch a future change to
        // the allocator (e.g. random IDs would break harness tests
        // that assert byte-identity against a known mapID).
        Player pl = PacketTestFixture.newPlayerWithZoneAndNpcs(5);
        List<NPC> npcs = pl.getZone().getAllNPCs();
        // NPCs are stored in a HashMap, so getAllNPCs() may return
        // them in any order. Sort by mapID for the assertion.
        java.util.List<Integer> mapIds = new java.util.ArrayList<>();
        for (NPC n : npcs) mapIds.add(n.getMapID());
        java.util.Collections.sort(mapIds);
        assertEquals(java.util.Arrays.asList(
                0x0101, 0x0102, 0x0103, 0x0104, 0x0105),
                mapIds);
    }

    @Test
    public void npcsHaveDeterministicPositions() {
        Player pl = PacketTestFixture.newPlayerWithZoneAndNpcs(3);
        for (NPC npc : pl.getZone().getAllNPCs()) {
            int i = npc.getMapID() - 0x0101;
            assertEquals("X = 1000 + i*10",
                    1000 + i * 10, npc.getXpos());
            assertEquals("Y = 2000 + i*10",
                    2000 + i * 10, npc.getYpos());
            assertEquals("Z = 3000 + i*10",
                    3000 + i * 10, npc.getZpos());
        }
    }

    @Test
    public void npcsHaveDefaultScriptAndModelNames() {
        // All fixture NPCs share scriptName "WSK", modelName "wsk.mdl"
        // — known retail NPC type names from the catalog (see
        // memory/project_protocol_catalog.md, udp_s2c_03_28.md).
        Player pl = PacketTestFixture.newPlayerWithZoneAndNpcs(3);
        for (NPC npc : pl.getZone().getAllNPCs()) {
            assertEquals("WSK", npc.getScriptName());
            assertEquals("wsk.mdl", npc.getModelName());
            assertEquals(42, npc.getType());
            assertEquals(100, npc.getHP());
            assertEquals(10, npc.getArmor());
            assertEquals(0, npc.getAngle());
        }
    }

    @Test
    public void newPlayerWithZoneAndNpcs_emitsPerNpcWorldNPCInfo() {
        // Functional verification: each NPC produces a distinct,
        // valid WorldNPCInfo packet. This is the primary use case
        // of the fixture method — the harness needs WorldNPCInfo
        // emissions per NPC to align against retail's queue.
        Player pl = PacketTestFixture.newPlayerWithZoneAndNpcs(3);
        java.util.Set<Integer> seenMapIds = new java.util.HashSet<>();
        for (NPC npc : pl.getZone().getAllNPCs()) {
            WorldNPCInfo pkt = new WorldNPCInfo(pl, npc);
            // Each emission is non-null and at least the 35B header
            // + 2 null-terminated strings (4B "WSK\0" + 8B "wsk.mdl\0").
            byte[] datagram = pkt.getDatagramPackets()[0]
                    .getData();
            int len = pkt.getDatagramPackets()[0].getLength();
            assertTrue("datagram size ≥ 47B (header + names)",
                    len >= 11 + 35 + 4 + 8);
            // Verify mapID embedded at body offset 2..3 matches.
            int mapId = (datagram[11 + 2] & 0xFF)
                    | ((datagram[11 + 3] & 0xFF) << 8);
            assertEquals(npc.getMapID(), mapId);
            assertTrue("mapID must be unique across NPCs",
                    seenMapIds.add(mapId));
        }
        assertEquals(3, seenMapIds.size());
    }
}
