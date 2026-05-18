package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;

/**
 * Functional test for the scripted-NPC spawn path
 * ({@code Zone.sendNPCinZone}).
 *
 * <p>Per RE_state_sync.md §1 / §4.2 the per-entity initial send must
 * emit, in order:
 * <ol>
 *   <li>the WWORLDMGR <b>Type-0x1E SCRIPTEDPLAYER create</b>
 *       ({@link ScriptedPlayerSpawn}) — the only message that
 *       instantiates the actor ({@code FUN_00541f20 case '\x1e'} &rarr;
 *       {@code FUN_00540ab0} &rarr; {@code FUN_00567e50} class-{@code
 *       0x100} &rarr; {@code FUN_00699fd0}); then</li>
 *   <li>the reliable {@code 0x03/0x28} WorldInfo and the 6-byte
 *       {@code 0x03/0x2d} ping ({@link ZoneStateCompoundPacket}) — the
 *       post-create refresh / spawn-request trigger (NOT a create).</li>
 * </ol>
 *
 * <p>{@code Zone.sendNPCinZone} is {@code pl.send(new
 * ScriptedPlayerSpawn(pl,npc)); pl.send(new
 * ZoneStateCompoundPacket(pl,npc));}. This test pins that exact
 * datagram sequence at the builder level (the same direct-builder
 * convention as {@link NpcRenderChainTest}, which the test fixture's
 * non-capturing {@code pl.send} requires).
 */
public class ScriptedNpcSpawnPathTest {

    private static int subOp(DatagramPacket dp) {
        // 0x13(1)+ctr(2)+ctr+sk(2)+subLen(2)+0x03(1)+seq(2) = 10
        return dp.getData()[10] & 0xFF;
    }

    /** Reproduce the exact datagram order Zone.sendNPCinZone emits. */
    private static List<Integer> spawnPathOps(Player pl, NPC npc) {
        List<Integer> ops = new ArrayList<>();
        // pl.send(new ScriptedPlayerSpawn(pl, npc));
        for (DatagramPacket dp :
                new ScriptedPlayerSpawn(pl, npc).getDatagramPackets()) {
            assertEquals("create is a 0x13 frame",
                    0x13, dp.getData()[0] & 0xFF);
            assertEquals("create is reliable 0x03",
                    0x03, dp.getData()[7] & 0xFF);
            ops.add(subOp(dp));
        }
        // pl.send(new ZoneStateCompoundPacket(pl, npc));
        for (DatagramPacket dp :
                new ZoneStateCompoundPacket(pl, npc)
                        .getDatagramPackets()) {
            assertEquals("refresh is a 0x13 frame",
                    0x13, dp.getData()[0] & 0xFF);
            assertEquals("refresh is reliable 0x03",
                    0x03, dp.getData()[7] & 0xFF);
            ops.add(subOp(dp));
        }
        return ops;
    }

    @Test
    public void spawnPathIsTypeOneECreateThenWorldInfoThenTick() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        // Retail scripted-city-NPC ids the client renders persistently.
        for (int id : new int[] { 0x010A, 0x012B, 0x0145 }) {
            NPC npc = new NPC(id, 7, 0x0b4a, "WSK", "WSK", "",
                    35440, 30880, 32528, -178, 100, 0);
            List<Integer> ops = spawnPathOps(pl, npc);
            assertEquals("id 0x" + Integer.toHexString(id)
                    + ": create(0x1e) THEN WorldInfo(0x28) THEN "
                    + "tick(0x2d)",
                    List.of(0x1E, 0x28, 0x2D), ops);
            assertFalse("id 0x" + Integer.toHexString(id)
                    + ": NO raw 0x1b on the spawn path",
                    ops.contains(0x1b));
            assertFalse("id 0x" + Integer.toHexString(id)
                    + ": NO 0x26 RemoveWorldItem on the spawn path",
                    ops.contains(0x26));
        }
    }

    @Test
    public void typeOneECreateIsExactlyOneDatagram() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(266, 7, 0x0b4a, "WSK", "WSK", "",
                35440, 30880, 32528, -178, 100, 0);
        DatagramPacket[] dps =
                new ScriptedPlayerSpawn(pl, npc).getDatagramPackets();
        assertEquals("create is a single reliable datagram",
                1, dps.length);
        assertEquals("create sub-op is WWORLDMGR Type 0x1E",
                0x1E, subOp(dps[0]));
    }
}
