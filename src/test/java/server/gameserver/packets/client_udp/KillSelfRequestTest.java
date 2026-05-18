package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketReaderUDP;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.GameServerEvent;

/**
 * Tests the documented {@code /set kill_self 1} ("instant suicide")
 * wire path {@code 0x03/0x1f/<localId>/0x3d} app-action sub-byte
 * {@code 0x10}:
 *
 * <ul>
 *   <li>byte-identity decode: the reader routes the binary-pinned
 *       packet to {@link KillSelfRequest};</li>
 *   <li>the sibling heartbeat sub-byte {@code 0x11} (and the status
 *       snapshot {@code 0x32}) still decode to {@code null} — i.e.
 *       the kill-self route does NOT regress the ~90 Hz heartbeat
 *       recognition;</li>
 *   <li>functional: executing the packet drops the caller's HP to 0
 *       (instant suicide via {@link Player#die()}).</li>
 * </ul>
 *
 * <p>The {@code 0x10} byte form is binary-pinned from a decompile of
 * {@code FUN_0065d710} / {@code FUN_006576f0} in
 * {@code neocronclient.exe}, NOT from the available
 * {@code RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (whose captured
 * player only sat + talked to an NPC and never used kill_self — its
 * only {@code 0x1f/0x3d} sub-bytes are {@code 0x11} ×2684 and
 * {@code 0x32} ×12). These tests pin the decode against that
 * binary-derived layout.
 */
public class KillSelfRequestTest {

    /**
     * {@code [0x03][seq LE2][0x1f][localId LE2][0x3d][subByte]
     * [00 00 00]} — the inner form the client's FUN_006576f0
     * emits ({@code [0x15][id&0x3ff][0x3d][10 00 00 00]}) once the
     * Winsock wrapper renumbers the opcode onto the standard
     * {@code 0x03/0x1f/.../0x3d} app-action channel.
     */
    private static byte[] appAction(int subByte) {
        return new byte[] {
            0x03, 0x0d, 0x00,            // reliable wrapper + seq
            0x1f, 0x03, 0x00,            // 0x1f + localId LE16
            0x3d,                        // app-action tag
            (byte) subByte,              // 0x10 = kill_self, 0x11 = hb
            0x00, 0x00, 0x00             // body
        };
    }

    @Test
    public void readerRoutesKillSelfToKillSelfRequest()
            throws Exception {
        Method m = GamePacketReaderUDP.class.getDeclaredMethod(
                "decodesub13", byte[].class);
        m.setAccessible(true);
        GameServerEvent ev = (GameServerEvent) m.invoke(
                null, (Object) appAction(0x10));
        assertNotNull("0x1f/0x3d/0x10 must decode", ev);
        assertTrue("kill_self must route to KillSelfRequest, got "
                        + ev.getClass().getName(),
                ev instanceof KillSelfRequest);
    }

    @Test
    public void heartbeatSubByteStillDecodesToNull()
            throws Exception {
        Method m = GamePacketReaderUDP.class.getDeclaredMethod(
                "decodesub13", byte[].class);
        m.setAccessible(true);
        // 0x11 (heartbeat) and 0x32 (status snapshot) must NOT be
        // mistaken for kill_self — they stay recognised-as-null.
        assertNull("0x1f/0x3d/0x11 heartbeat must stay null",
                (GameServerEvent) m.invoke(
                        null, (Object) appAction(0x11)));
        assertNull("0x1f/0x3d/0x32 status snapshot must stay null",
                (GameServerEvent) m.invoke(
                        null, (Object) appAction(0x32)));
    }

    @Test
    public void executingKillSelfDropsHpToZero() {
        Player pl = PacketTestFixture.newPlayer();
        assertTrue("precondition: alive",
                pl.getCharacter().getHealth() > 0);

        new KillSelfRequest(appAction(0x10)).execute(pl);

        assertEquals("instant suicide → HP 0",
                0, pl.getCharacter().getHealth());
    }

    @Test
    public void nullCharacterIsIgnoredNotCrashing() {
        // A KillSelfRequest with no attached character must be a
        // no-op, never an NPE (mirrors AdminCommandRequest's
        // defensive null-guard convention).
        new KillSelfRequest(appAction(0x10)).execute((Player) null);
    }
}
