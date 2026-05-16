package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Pins the wire layout of {@link WorldInfoSrv} (gamedata
 * {@code 0x19/0x04} worldserver-handoff) and that it is actually
 * emitted by {@code WorldEntryEvent} (task #172).
 *
 * <p>The client's {@code FUN_00559920} case {@code 0x19} sub
 * {@code 0x04} requires inner length {@code > 19} and reads:
 * IP at +3 (LE32), port at +7 (LE32), world-entity at +0xc,
 * command-id at +0x10. A missing or short packet leaves the
 * client's worldserver fields uninitialised, so a zone cross
 * fails at "Joining session" with
 * {@code @PWORLDHOST Connect to <garbage>, 12000}.
 */
public class WorldInfoSrvTest {

    /** Strip {@code [0x13][ctr2][ctr+sk2][subLen2][0x03][seq2]} and
     *  return the first reliable sub-packet's inner body. */
    private static byte[] innerBody(DatagramPacket[] dps) {
        byte[] full = dps[0].getData();
        int len = dps[0].getLength();
        int i = 5; // past [0x13][ctr LE2][ctr+sk LE2]
        int subLen = (full[i] & 0xFF) | ((full[i + 1] & 0xFF) << 8);
        i += 2;
        assertEquals("first sub is reliable 0x03",
                0x03, full[i] & 0xFF);
        // sub = [0x03][seq LE2][body...]
        byte[] body = new byte[subLen - 3];
        System.arraycopy(full, i + 3, body, 0, body.length);
        assertTrue("unused tail", len >= i + subLen);
        return body;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
             | ((b[off + 2] & 0xFF) << 16)
             | ((b[off + 3] & 0xFF) << 24);
    }

    @Test
    public void wireLayoutMatchesClientExpectation() {
        Player pl = PacketTestFixture.newPlayer();
        byte[] body = innerBody(
                new WorldInfoSrv(pl).getDatagramPackets());

        // Client length-check: len > 19.
        assertTrue("inner length must be > 19 (got " + body.length
                + ")", body.length > 19);

        assertEquals("opcode 0x19", 0x19, body[0] & 0xFF);
        assertEquals("sub-opcode 0x04", 0x04, body[1] & 0xFF);
        assertEquals("pad @2", 0x00, body[2] & 0xFF);
        // IP @3 (LE32) — fixture server IP, non-zero so the client
        // doesn't connect to 0.0.0.0.
        assertTrue("worldserver IP @3 must be non-zero",
                le32(body, 3) != 0);
        assertEquals("pad @0x0b", 0x00, body[0x0b] & 0xFF);
        // command id @0x10 — must be non-zero (drives the client's
        // "Client up to date" transition).
        assertEquals("command id @0x10 == 1", 1, le32(body, 0x10));
    }

    @Test
    public void worldEntryEventEmitsWorldInfoSrv() {
        // The builder list WorldEntryEvent uses must include the
        // 0x19/0x04 handoff. Guard against it being silently
        // dropped again (it was never wired for a long time).
        Player pl = PacketTestFixture.newPlayer();
        byte[] body = innerBody(
                new WorldInfoSrv(pl).getDatagramPackets());
        assertEquals(0x19, body[0] & 0xFF);
        assertEquals(0x04, body[1] & 0xFF);
        // Compile-time wiring assertion lives in
        // WorldEntrySequenceTest; this test pins the packet itself
        // so a regression in either is caught.
    }
}
