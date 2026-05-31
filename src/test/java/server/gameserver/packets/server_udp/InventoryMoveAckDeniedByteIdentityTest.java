package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.net.DatagramPacket;

import org.junit.Before;
import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link InventoryMoveAck} and
 * {@link InventoryMoveDenied}.
 *
 * <p>As of the 2026-05 inventory-move fix these are NO LONGER the
 * same sub-channel:
 * <ul>
 *   <li>{@link InventoryMoveAck} now emits the doc-derived
 *       {@code 1f [mapid LE2] 25 1e [dstCont][dstPos LE2]} echo
 *       (act_tag 0x25, sub-action 0x1e). UNVERIFIED against a retail
 *       F2-drag pcap — body is doc-derived.</li>
 *   <li>{@link InventoryMoveDenied} is unchanged — the
 *       {@code 25 13 [txn] 14 …} txn-wrapper deny.</li>
 * </ul>
 * They therefore no longer share a prefix; the old shared-prefix
 * assertions were removed.
 */
public class InventoryMoveAckDeniedByteIdentityTest {

    @Before
    public void resetTransactionId() throws Exception {
        // Player.Transactionid is a per-instance field; tests
        // build fresh players via PacketTestFixture so each
        // test starts at the constructor's default. No global
        // reset needed.
    }

    private static byte[] datagramBytes(server.networktools.PacketBuilderUDP pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP13 only):
     *  {@code [0x13][counter LE2][counter+sk LE2][size LE2][body...]}
     *  Body starts at offset 7. */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13", 0x13, datagram[0] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 7, body, 0, len);
        return body;
    }

    private static void setTxn(Player pl, int v) throws Exception {
        Field f = Player.class.getDeclaredField("Transactionid");
        f.setAccessible(true);
        f.setShort(pl, (short) v);
    }

    @Test
    public void ackBodyLayout() throws Exception {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0xABCD);

        // New 0x25/0x1e InventoryMove-echo form. Body is 7 bytes:
        //   1f [mapid LE2] 25 1e [dstCont][dstPos LE2]
        // NOTE: doc-derived (udp_s2c_03_1f.md / _funcref_subtags.md);
        // UNVERIFIED against a retail F2-drag pcap.
        byte[] body = extractInnerBody(datagramBytes(
                new InventoryMoveAck(pl,
                        0x05,   // srcCont (no longer echoed)
                        0x0102, // srcPos (no longer echoed)
                        0x06,   // dstCont
                        0x0304  // dstPos
                )), 7);
        // [0]    0x1f outer sub-opcode
        // [1..2] mapId LE16 (CD AB for 0xABCD)
        // [3]    0x25 act_tag
        // [4]    0x1e sub-action (InventoryMove echo)
        // [5]    dstCont
        // [6..7] dstPos LE16  (only [6] is within the 7-byte body;
        //        dstPos low byte at [6], high byte would be [7])
        assertEquals(0x1f, body[0] & 0xFF);
        assertEquals(0xCD, body[1] & 0xFF);
        assertEquals(0xAB, body[2] & 0xFF);
        assertEquals(0x25, body[3] & 0xFF);
        assertEquals(0x1e, body[4] & 0xFF);
        assertEquals(0x06, body[5] & 0xFF);
        // dstPos LE16 = 0x0304 → low 0x04, high 0x03
        assertEquals(0x04, body[6] & 0xFF);
    }

    @Test
    public void ackDstPosHighByte() throws Exception {
        // Verify the full 8-byte ack body including dstPos high byte.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0xABCD);
        byte[] body = extractInnerBody(datagramBytes(
                new InventoryMoveAck(pl, 0, 0, 0x06, 0x0304)), 8);
        assertEquals(0x06, body[5] & 0xFF); // dstCont
        assertEquals(0x04, body[6] & 0xFF); // dstPos lo
        assertEquals(0x03, body[7] & 0xFF); // dstPos hi
    }

    @Test
    public void deniedBodyLayout() throws Exception {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(1);
        setTxn(pl, 0);

        byte[] body = extractInnerBody(datagramBytes(
                new InventoryMoveDenied(pl, 1, 2, 3, 4)), 15);
        // Same prefix as ack, but trailer is single 0x00
        assertEquals(0x1f, body[0]  & 0xFF);
        assertEquals(0x14, body[7]  & 0xFF);
        // Denied trailer
        assertEquals("denied trailer is single 0x00",
                0x00, body[14] & 0xFF);
    }

    @Test
    public void ackAndDeniedShareActTagButDifferentSubAction() throws Exception {
        // Ack and denied now diverge immediately after the 0x25 act_tag:
        // ack uses sub-action 0x1e (InventoryMove echo), denied keeps the
        // 0x13 txn-wrapper. Both still share 1f [mapid LE2] 25.
        Player pl1 = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        Player pl2 = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl1.setMapID(0x1234);
        pl2.setMapID(0x1234);
        setTxn(pl2, 0x4243);

        byte[] ack = extractInnerBody(datagramBytes(
                new InventoryMoveAck(pl1, 5, 6, 7, 8)), 5);
        byte[] denied = extractInnerBody(datagramBytes(
                new InventoryMoveDenied(pl2, 5, 6, 7, 8)), 5);

        // Shared prefix 1f [mapid LE2] 25.
        for (int i = 0; i < 4; i++) {
            assertEquals("byte " + i + " of shared prefix",
                    ack[i], denied[i]);
        }
        // Divergent sub-action byte.
        assertEquals(0x1e, ack[4] & 0xFF);
        assertEquals(0x13, denied[4] & 0xFF);
    }

    @Test
    public void totalAckSizeIsFifteenBytes() throws Exception {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   8 (body: 1f [mapid LE2] 25 1e [dstCont][dstPos LE2]) = 15
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        setTxn(pl, 0);
        assertEquals(15, datagramBytes(
                new InventoryMoveAck(pl, 0, 0, 0, 0)).length);
    }

    @Test
    public void totalDeniedSizeIsTwentyTwoBytes() throws Exception {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   15 (body) = 22 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        setTxn(pl, 0);
        assertEquals(22, datagramBytes(
                new InventoryMoveDenied(pl, 0, 0, 0, 0)).length);
    }

    @Test
    public void deniedTxnIncrementsAcrossInstances() throws Exception {
        // InventoryMoveDenied still uses the 0x25/0x13 txn-wrapper and
        // increments the per-player transaction id (the ack no longer
        // does — its 0x25/0x1e form carries no txn).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        setTxn(pl, 0);

        byte[] b1 = extractInnerBody(datagramBytes(
                new InventoryMoveDenied(pl, 0, 0, 0, 0)), 15);
        byte[] b2 = extractInnerBody(datagramBytes(
                new InventoryMoveDenied(pl, 0, 0, 0, 0)), 15);

        // txn at body offset 5..6 LE16
        int txn1 = (b1[5] & 0xFF) | ((b1[6] & 0xFF) << 8);
        int txn2 = (b2[5] & 0xFF) | ((b2[6] & 0xFF) << 8);
        assertEquals("first txn = 1 (initial 0 incremented)",
                1, txn1);
        assertEquals("second txn = 2",
                2, txn2);
    }
}
