package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;

/**
 * Functional test for the task #178 NPC-render chain.
 *
 * <p>Pins the retail invariant byte-decoded 2026-05-17 from
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (server
 * 157.90.195.74) for the scripted-city-NPC class the client renders
 * persistently (entities 266 "WSK" / 299 "WCOP" / 325
 * "PATROL_COPBOT6"):
 *
 * <ul>
 *   <li>spawn + every refresh = reliable {@code 0x03/0x28} WorldInfo
 *       (the Type-15 create) THEN a reliable {@code 0x03/0x2d}
 *       6-byte ping;</li>
 *   <li>NEVER a raw {@code 0x1b} broadcast for the NPC id;</li>
 *   <li>NEVER a {@code 0x03/0x26 RemoveWorldItem} for a live NPC.</li>
 * </ul>
 *
 * <p>The pre-#178d emitter produced a raw {@code 0x1b} per NPC which
 * registered it in the client's transient world-item table; the
 * client GC'd that within a frame — the "appears then disappears"
 * symptom. A spurious {@code 0x26} was never the cause (it only ever
 * fires on a real {@code MobDeathIntent}); the despawn was entirely
 * client-side, triggered by the spurious {@code 0x1b}.
 */
public class NpcRenderChainTest {

    private static int subOp(DatagramPacket dp) {
        // 0x13(1)+ctr(2)+ctr+sk(2)+subLen(2)+0x03(1)+seq(2) = 10
        return dp.getData()[10] & 0xFF;
    }

    /** Collect the sub-opcode of every datagram a spawn/refresh
     *  emits for one NPC. */
    private static List<Integer> chainFor(int npcId) {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(1234, -567, 89, 100, 0, 20, npcId);
        DatagramPacket[] dps =
                new ZoneStateCompoundPacket(pl, npc)
                        .getDatagramPackets();
        List<Integer> ops = new ArrayList<>();
        for (DatagramPacket dp : dps) {
            assertEquals("every datagram is a 0x13 frame",
                    0x13, dp.getData()[0] & 0xFF);
            assertEquals("every datagram is reliable 0x03",
                    0x03, dp.getData()[7] & 0xFF);
            ops.add(subOp(dp));
        }
        return ops;
    }

    @Test
    public void spawnChainIsCreateThenTick_noRemove_no1b() {
        // Three retail NPC ids that the client renders persistently.
        for (int id : new int[] { 0x010A, 0x012B, 0x0145 }) {
            List<Integer> ops = chainFor(id);

            assertEquals("id 0x" + Integer.toHexString(id)
                    + ": exactly create(0x28)+tick(0x2d)",
                    List.of(0x28, 0x2d), ops);

            assertFalse("id 0x" + Integer.toHexString(id)
                    + ": NO raw 0x1b (the despawn trigger)",
                    ops.contains(0x1b));
            assertFalse("id 0x" + Integer.toHexString(id)
                    + ": NO 0x26 RemoveWorldItem for a live NPC",
                    ops.contains(0x26));
        }
    }

    @Test
    public void repeatedRefreshesNeverEmitARemoveOrA1b() {
        // Simulate many heartbeat ticks for the same live NPC: not
        // one of them may emit a 0x1b or a 0x26 (retail re-sends
        // 0x28+0x2d only, indefinitely, for a stationary NPC).
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(50, 60, 70, 100, 0, 20, 0x010A);
        for (int tick = 0; tick < 50; tick++) {
            DatagramPacket[] dps =
                    new ZoneStateCompoundPacket(pl, npc)
                            .getDatagramPackets();
            assertEquals("tick " + tick + ": 2 datagrams",
                    2, dps.length);
            assertEquals("tick " + tick + ": [0]=0x28",
                    0x28, subOp(dps[0]));
            assertEquals("tick " + tick + ": [1]=0x2d",
                    0x2d, subOp(dps[1]));
        }
    }

    @Test
    public void the2dTickIdMatchesThe28CreateId() {
        // The client creates the SCRIPTEDPLAYER actor from the
        // 0x28[3..4] world-object id and ages it out unless the
        // 0x2d[1..2] ping carries the SAME id. A mismatch is the
        // appear-then-vanish bug in a different guise.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        int id = 0x0145;
        NPC npc = new NPC(1, 2, 3, 100, 0, 1, id);
        DatagramPacket[] dps =
                new ZoneStateCompoundPacket(pl, npc)
                        .getDatagramPackets();
        byte[] w = dps[0].getData();
        byte[] t = dps[1].getData();
        int worldId = (w[13] & 0xFF) | ((w[14] & 0xFF) << 8); // doc[3..4]
        int tickId  = (t[11] & 0xFF) | ((t[12] & 0xFF) << 8); // 0x2d[1..2]
        assertEquals("0x28 world-object id", id, worldId);
        assertEquals("0x2d ping entity id", id, tickId);
        assertEquals(worldId, tickId);
    }
}
