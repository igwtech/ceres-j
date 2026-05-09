package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link DamageTick} — the short
 * combat-tick damage notification fired ~every 500 ms while
 * a character is taking damage.
 *
 * <p>Wire format (6-byte inner body after {@code 0x1f}):
 * <pre>
 *   1f 01 00 25 23 30
 * </pre>
 *
 * <p>Drives the client's HUD HP-bar animation. Without it the
 * HP bar doesn't tick down even when the server applies damage.
 */
public class DamageTickByteIdentityTest {

    private static byte[] datagramBytes(DamageTick pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP1303):
     *  {@code [0x13][counter LE2][counter+sk LE2][size LE2][0x03][seq LE2][0x1f][body...]}
     *  Body starts at offset 11. */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x1f", 0x1f, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void exactRetailBytes() {
        // Catalog/source comment: 1f 01 00 25 23 30
        // After 0x1f sub-opcode is consumed by the wrapper, the
        // body is `01 00 25 23 30` (5 bytes).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new DamageTick(pl)), 5);
        byte[] expected = {
                0x01, 0x00, 0x25, 0x23, 0x30
        };
        assertArrayEquals("body must match retail damage-tick "
                + "verbatim (1f 01 00 25 23 30 inner)", expected, body);
    }

    @Test
    public void totalDatagramSizeIsSixteenBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x1f) + 5 (body) = 16 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(16, datagramBytes(new DamageTick(pl)).length);
    }

    @Test
    public void bodyIsPureConstantAcrossInstances() {
        // The constructor takes only a Player (for the session
        // counter); body bytes don't depend on Player state.
        // Different Players → identical body bytes.
        Player pl1 = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        Player pl2 = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body1 = extractInnerBody(
                datagramBytes(new DamageTick(pl1)), 5);
        byte[] body2 = extractInnerBody(
                datagramBytes(new DamageTick(pl2)), 5);
        assertArrayEquals(body1, body2);
    }

    @Test
    public void subOpcodePinned0x25_0x23_0x30Trailer() {
        // The 0x25/0x23/0x30 sub-opcode triplet is the
        // "damage-occurring pulse" identifier. A future
        // refactor that swaps these to a different combat
        // sub-tag must fail loudly.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new DamageTick(pl)), 5);
        assertEquals("0x25 combat opcode at offset 2",
                0x25, body[2] & 0xFF);
        assertEquals("0x23 sub-opcode at offset 3",
                0x23, body[3] & 0xFF);
        assertEquals("0x30 trailer at offset 4",
                0x30, body[4] & 0xFF);
    }
}
