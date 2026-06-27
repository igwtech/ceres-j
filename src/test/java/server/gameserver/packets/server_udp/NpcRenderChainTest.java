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
 * <p>Scope: this pins the {@link ZoneStateCompoundPacket} bundle for
 * the scripted-city-NPC class the client renders persistently
 * (entities 266 "WSK" / 299 "WCOP" / 325 "PATROL_COPBOT6"), decoded
 * from {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (server
 * 157.90.195.74):
 *
 * <ul>
 *   <li>the COMPOUND bundle = reliable {@code 0x03/0x28} WorldInfo
 *       (the Type-15 create) THEN a reliable {@code 0x03/0x2d}
 *       6-byte ping — and nothing else in that datagram;</li>
 *   <li>the compound itself never carries a {@code 0x1b} or a
 *       {@code 0x03/0x26 RemoveWorldItem}.</li>
 * </ul>
 *
 * <p><b>Correction (2026-06-14):</b> an earlier note here claimed
 * retail "NEVER" sends a raw {@code 0x1b} for a scripted NPC id. That
 * was wrong — re-decoding the SAME pcap finds 22 scripted ids
 * (&ge; {@code 0x0100}) in 749 raw {@code 0x1b} placement records
 * (WSK {@code 0x010a} &times;92), with Y/Z/X byte-identical to their
 * {@code 0x28}. The client INSTANTIATES the renderable actor from that
 * {@code 0x1b}; the {@code 0x28}+{@code 0x2d} then name + script it.
 * The raw {@code 0x1b} is emitted SEPARATELY (a sibling
 * {@link SendPresentWorldID} datagram) by {@code Zone.sendNPCinZone},
 * NOT inside this compound — so the "compound has no 0x1b" assertions
 * below remain valid <em>in scope</em>. The pre-#178d
 * "appears then disappears" symptom was the OLD MALFORMED 0x1b body
 * (route {@code 0x42} + HP/random tail) being GC'd, not the 0x1b's
 * presence.
 */
public class NpcRenderChainTest {

    /** Walk a bundled 0x13 datagram's reliable sub-packets and
     *  return each one's inner op byte. 2026-05-30 bundling fix:
     *  ZoneStateCompoundPacket now emits ONE datagram with multiple
     *  sub-packets (was one-per-sub pre-fix). */
    private static List<Integer> subOpsOf(DatagramPacket dp) {
        List<Integer> ops = new ArrayList<>();
        byte[] d = dp.getData();
        int len = dp.getLength();
        int i = 5; // past [0x13][ctr LE2][ctr+sk LE2]
        while (i + 2 <= len) {
            int subLen = (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
            i += 2;
            if (subLen <= 0 || i + subLen > len) break;
            if (subLen >= 4 && (d[i] & 0xFF) == 0x03) {
                // body op = sub[3] (after [0x03][seq LE2])
                ops.add(d[i + 3] & 0xFF);
            }
            i += subLen;
        }
        return ops;
    }

    /** Single reliable sub-op of a datagram that carries exactly one
     *  sub-packet. Back-compat with the old single-sub assertions. */
    private static int subOp(DatagramPacket dp) {
        return subOpsOf(dp).get(0);
    }

    /** Walk a bundled 0x13 datagram and return each reliable
     *  sub-packet's body (post {@code 0x03 [seq LE2]}). Each entry
     *  starts with the inner op byte at index 0. */
    private static List<byte[]> subBodiesOf(DatagramPacket dp) {
        List<byte[]> out = new ArrayList<>();
        byte[] d = dp.getData();
        int len = dp.getLength();
        int i = 5; // past [0x13][ctr LE2][ctr+sk LE2]
        while (i + 2 <= len) {
            int subLen = (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
            i += 2;
            if (subLen <= 0 || i + subLen > len) break;
            if (subLen >= 3 && (d[i] & 0xFF) == 0x03) {
                byte[] body = new byte[subLen - 3];
                System.arraycopy(d, i + 3, body, 0, body.length);
                out.add(body);
            }
            i += subLen;
        }
        return out;
    }

    /** Collect the sub-opcode of every reliable sub-packet a
     *  spawn/refresh emits for one NPC. */
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
            assertEquals("every datagram is reliable 0x03 at first sub",
                    0x03, dp.getData()[7] & 0xFF);
            ops.addAll(subOpsOf(dp));
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
                    + ": the COMPOUND bundle carries no 0x1b (the raw"
                    + " 0x1b placement is a separate SendPresentWorldID"
                    + " datagram, see Zone.sendNPCinZone)",
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
        // 2026-05-30 bundling: each tick = 1 datagram with [0x28, 0x2d]
        // sub-packets (was 2 separate datagrams pre-fix).
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(50, 60, 70, 100, 0, 20, 0x010A);
        for (int tick = 0; tick < 50; tick++) {
            DatagramPacket[] dps =
                    new ZoneStateCompoundPacket(pl, npc)
                            .getDatagramPackets();
            assertEquals("tick " + tick + ": 1 bundled datagram",
                    1, dps.length);
            List<Integer> ops = subOpsOf(dps[0]);
            assertEquals("tick " + tick + ": 2 sub-packets",
                    2, ops.size());
            assertEquals("tick " + tick + ": sub 0 = 0x28",
                    Integer.valueOf(0x28), ops.get(0));
            assertEquals("tick " + tick + ": sub 1 = 0x2d",
                    Integer.valueOf(0x2d), ops.get(1));
        }
    }

    @Test
    public void the2dTickIdMatchesThe28CreateId() {
        // The client creates the SCRIPTEDPLAYER actor from the
        // 0x28[3..4] world-object id and ages it out unless the
        // 0x2d[1..2] ping carries the SAME id. A mismatch is the
        // appear-then-vanish bug in a different guise.
        // Bundled wire layout walk: read sub bodies via
        // subBodiesOf(dp) — same content as before, different framing.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        int id = 0x0145;
        NPC npc = new NPC(1, 2, 3, 100, 0, 1, id);
        DatagramPacket[] dps =
                new ZoneStateCompoundPacket(pl, npc)
                        .getDatagramPackets();
        java.util.List<byte[]> bodies = subBodiesOf(dps[0]);
        byte[] w = bodies.get(0);  // 28 0001 [id LE2] ...
        byte[] t = bodies.get(1);  // 2d [id LE2] 0000 06
        int worldId = (w[3] & 0xFF) | ((w[4] & 0xFF) << 8);
        int tickId  = (t[1] & 0xFF) | ((t[2] & 0xFF) << 8);
        assertEquals("0x28 world-object id", id, worldId);
        assertEquals("0x2d ping entity id", id, tickId);
        assertEquals(worldId, tickId);
    }
}
