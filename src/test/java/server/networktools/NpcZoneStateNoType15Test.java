package server.networktools;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertEquals;

import java.net.DatagramPacket;
import java.util.List;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.server_udp.ChatList;
import server.gameserver.packets.server_udp.InfoResponse;
import server.gameserver.packets.server_udp.LongPlayerInfo;
import server.gameserver.packets.server_udp.NpcDataBroadcast;
import server.gameserver.packets.server_udp.ObjectPositionBroadcast;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.PlayerPositionUpdate;
import server.gameserver.packets.server_udp.SMovement;
import server.gameserver.packets.server_udp.SZoning1;
import server.gameserver.packets.server_udp.ShortPlayerInfo;
import server.gameserver.packets.server_udp.TimeSync;
import server.gameserver.packets.server_udp.WorldNPCInfo;
import server.gameserver.packets.server_udp.WorldWeather;
import server.gameserver.packets.server_udp.ZoneStateCompoundPacket;

/**
 * Task #193 regression: the client's WWORLDMGR dispatcher
 * ({@code FUN_00541f20}, RE_state_sync.md §1) has <strong>no
 * {@code case 0x0F}</strong>. Any dequeued application message whose
 * {@code body[0] == 0x0F} (decimal 15) logs
 * {@code "@WWORLDMGR : Corrupted Message Type:15, Size:<n>"} and is
 * dropped.
 *
 * <p>This test drives <em>every</em> Ceres NPC-spawn, zone-state,
 * heartbeat and world-entry reliable/raw emitter (single-sub,
 * multi-sub via {@link SZoning1}, and the {@link ZoneStateCompoundPacket}
 * spawn+tick chain) and runs each emitted datagram through the
 * byte-exact client model {@link ClientFrameDecoder} (steps 3–5 of the
 * receive→enqueue→dequeue chain pinned in RE_state_sync.md §0.3 /
 * task #190). It asserts the two invariants
 * {@code FUN_00541f20}/{@code FUN_004b8cd0} enforce:
 *
 * <ul>
 *   <li>no dequeued message has {@code body[0] == 0x0F} — i.e. nothing
 *       on the NPC/zone-state path can ever surface as
 *       "Corrupted Message Type:15";</li>
 *   <li>every dequeued message has {@code size == body.length} — no
 *       off-by-N that would slide an interior byte into the Type
 *       position.</li>
 * </ul>
 *
 * <p><strong>Evidence (task #193).</strong> The retail capture
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} contains
 * exactly one 21-byte S→C message — {@code w=0x03 op=0x1f}, body
 * {@code 1f 01 00 18 d5 01 00 00 74 a1 88 5f 00 00 00 00 4e 43 50 44 00}
 * ("…NCPD\0", a reliable per-entity SCRIPTEDPLAYER {@code 0x1f}
 * NetMessage, {@code body[0]==0x1f}, a valid WWORLDMGR Type). Ceres
 * has no emitter for that per-entity {@code 0x1f} create (it sends
 * only the {@code 0x28} WorldInfo, a spawn-<em>request</em> trigger per
 * RE_state_sync.md §1/§4.2). No Ceres emitter on any
 * harness-drivable path produces {@code body[0]==0x0F} — this test
 * regression-locks that and fails with the exact emitter + sub index
 * if a future change reintroduces a misframe.
 */
public class NpcZoneStateNoType15Test {

    @SuppressWarnings("unchecked")
    private static Zone zoneWithNpcs(int n) {
        Zone z = new Zone(101, "plaza_p3");
        try {
            java.lang.reflect.Field f =
                    Zone.class.getDeclaredField("NPCList");
            f.setAccessible(true);
            java.util.TreeMap<Integer, NPC> m =
                    (java.util.TreeMap<Integer, NPC>) f.get(z);
            for (int k = 0; k < n; k++) {
                int id = 0x0101 + k;
                m.put(id, new NPC(id, 101, 0x003b,
                        "WSK", "WSK", "", 1, 2, 3, 1, 100, 0));
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return z;
    }

    /** Decode every datagram and assert the WWORLDMGR invariants. */
    private static void assertNoType15(String label,
            DatagramPacket[] dps) {
        for (int d = 0; d < dps.length; d++) {
            byte[] one = new byte[dps[d].getLength()];
            System.arraycopy(dps[d].getData(), dps[d].getOffset(),
                    one, 0, one.length);
            List<ClientFrameDecoder.Message> msgs =
                    ClientFrameDecoder.decode(one);
            for (int s = 0; s < msgs.size(); s++) {
                ClientFrameDecoder.Message m = msgs.get(s);
                assertEquals(label + " dg[" + d + "] sub[" + s
                        + "]: Size: must equal real body length "
                        + "(no off-by-N that slides an interior "
                        + "byte into the WWORLDMGR Type position) — "
                        + "body=" + PacketTestFixture.hex(m.body),
                        m.body.length, m.size);
                assertNotEquals(label + " dg[" + d + "] sub[" + s
                        + "]: body[0] must NOT be 0x0F — "
                        + "FUN_00541f20 has no case 0x0F so this "
                        + "would log \"Corrupted Message Type:15, "
                        + "Size:" + m.size + "\"; body="
                        + PacketTestFixture.hex(m.body),
                        0x0F, m.type());
            }
        }
    }

    @Test
    public void npcSpawnChainNeverDequeuesType15() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);

        // Sweep scripted-NPC ids, script names and angles — the body
        // length (and therefore every interior byte's position) varies
        // with the trailing script_name\0 + angle\0 tokens.
        for (int id : new int[] { 0x010A, 0x012B, 0x0145, 0x01FF }) {
            for (String s : new String[] { "", "A", "WSK", "WCOP",
                    "PATROL_COPBOT6", "STATIC", "NCPD" }) {
                for (int ang : new int[] { 0, 1, -178, 270, -1 }) {
                    NPC npc = new NPC(id, 1, 0x003b, s, s, "",
                            1, 2, 3, ang, 100, 0);
                    String tag = "ZoneStateCompound id=0x"
                            + Integer.toHexString(id) + " s='" + s
                            + "' ang=" + ang;
                    assertNoType15(tag,
                            new ZoneStateCompoundPacket(pl, npc)
                                    .getDatagramPackets());
                    assertNoType15("WorldNPCInfo " + tag,
                            new WorldNPCInfo(pl, npc)
                                    .getDatagramPackets());
                    assertNoType15("NpcDataBroadcast " + tag,
                            new NpcDataBroadcast(pl, npc)
                                    .getDatagramPackets());
                    assertNoType15("ObjectPositionBroadcast " + tag,
                            new ObjectPositionBroadcast(pl, npc)
                                    .getDatagramPackets());
                }
            }
        }
    }

    @Test
    public void zoneStateHeartbeatRepeatsNeverDequeueType15() {
        // 60 heartbeat ticks for the same NPC: the seq/octr counters
        // advance every tick, so this also exercises subLen/seq
        // back-patching stability across many emissions.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(0x010A, 1, 0x003b, "WSK", "WSK", "",
                1, 2, 3, 1, 100, 0);
        for (int tick = 0; tick < 60; tick++) {
            assertNoType15("heartbeat tick " + tick,
                    new ZoneStateCompoundPacket(pl, npc)
                            .getDatagramPackets());
        }
    }

    @Test
    public void multiSubZoneCrossConfirmNeverDequeuesType15() {
        // SZoning1 is the only multi-sub (newSubPacket) emitter: an
        // off-by-N in any sub's back-patched subLen would slide every
        // later sub and turn an interior byte into a bogus Type.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        for (int nNpc : new int[] { 0, 1, 2, 5, 8, 16 }) {
            assertNoType15("SZoning1 destZone npcs=" + nNpc,
                    new SZoning1(1, pl, zoneWithNpcs(nNpc))
                            .getDatagramPackets());
        }
    }

    @Test
    public void worldEntryAndPlayerStateNeverDequeueType15() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();

        assertNoType15("LongPlayerInfo",
                new LongPlayerInfo(pl, pc, 2).getDatagramPackets());
        assertNoType15("ShortPlayerInfo",
                new ShortPlayerInfo(pl, pc, 2).getDatagramPackets());
        assertNoType15("PlayerPositionUpdate",
                new PlayerPositionUpdate(pl, pc, 2)
                        .getDatagramPackets());
        assertNoType15("SMovement",
                new SMovement(pl, pc, 2).getDatagramPackets());
        assertNoType15("TimeSync",
                new TimeSync(pl, 0).getDatagramPackets());
        assertNoType15("ChatList",
                new ChatList(pl).getDatagramPackets());
        assertNoType15("WorldWeather",
                new WorldWeather(pl).getDatagramPackets());
        assertNoType15("InfoResponse.zoneInfo",
                InfoResponse.zoneInfo(pl).getDatagramPackets());
        assertNoType15("InfoResponse.sessionInfo",
                InfoResponse.sessionInfo(pl).getDatagramPackets());
        assertNoType15("InfoResponse.postTransitionInfo",
                InfoResponse.postTransitionInfo(pl)
                        .getDatagramPackets());
        assertNoType15("InfoResponse.zoneTransitionMeta",
                InfoResponse.zoneTransitionMeta(pl)
                        .getDatagramPackets());
    }
}
