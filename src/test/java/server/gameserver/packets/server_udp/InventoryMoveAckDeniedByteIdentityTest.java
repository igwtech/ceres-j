package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.net.DatagramPacket;

import org.junit.Before;
import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link InventoryMoveAck} and
 * {@link InventoryMoveDenied} — the paired raw {@code 0x1f}
 * inventory-move responses. The two packets share the same
 * 14-byte prefix and differ only in the trailer
 * ({@code 01 00 00} for ack, single {@code 00} for denied).
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
        // After incrementTransactionID, txn becomes initial+1.
        // Set to 0x1233 so the wire shows 0x1234.
        setTxn(pl, 0x1233);

        byte[] body = extractInnerBody(datagramBytes(
                new InventoryMoveAck(pl,
                        0x05,   // srcCont
                        0x0102, // srcPos
                        0x06,   // dstCont
                        0x0304  // dstPos
                )), 17);
        // [0]    0x1f outer sub-opcode
        // [1..2] mapId LE16 (CD AB for 0xABCD)
        // [3..4] 0x25 0x13
        // [5..6] txn LE16
        // [7]    0x14 sub-tag (inventory-move)
        // [8]    srcCont
        // [9..10] srcPos LE16
        // [11]   dstCont
        // [12..13] dstPos LE16
        // [14..16] trailer 0x01 0x00 0x00
        assertEquals(0x1f, body[0]  & 0xFF);
        assertEquals(0xCD, body[1]  & 0xFF);
        assertEquals(0xAB, body[2]  & 0xFF);
        assertEquals(0x25, body[3]  & 0xFF);
        assertEquals(0x13, body[4]  & 0xFF);
        assertEquals(0x34, body[5]  & 0xFF);
        assertEquals(0x12, body[6]  & 0xFF);
        assertEquals(0x14, body[7]  & 0xFF);
        assertEquals(0x05, body[8]  & 0xFF);
        assertEquals(0x02, body[9]  & 0xFF);
        assertEquals(0x01, body[10] & 0xFF);
        assertEquals(0x06, body[11] & 0xFF);
        assertEquals(0x04, body[12] & 0xFF);
        assertEquals(0x03, body[13] & 0xFF);
        // Ack-specific trailer
        assertEquals("ack trailer byte 0",
                0x01, body[14] & 0xFF);
        assertEquals("ack trailer byte 1",
                0x00, body[15] & 0xFF);
        assertEquals("ack trailer byte 2",
                0x00, body[16] & 0xFF);
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
    public void ackAndDeniedSharePrefixThroughDstPos() throws Exception {
        Player pl1 = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        Player pl2 = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl1.setMapID(0x1234);
        pl2.setMapID(0x1234);
        setTxn(pl1, 0x4243);
        setTxn(pl2, 0x4243);

        byte[] ack = extractInnerBody(datagramBytes(
                new InventoryMoveAck(pl1, 5, 6, 7, 8)), 17);
        byte[] denied = extractInnerBody(datagramBytes(
                new InventoryMoveDenied(pl2, 5, 6, 7, 8)), 15);

        // Compare bytes [0..13] — the shared prefix.
        for (int i = 0; i < 14; i++) {
            assertEquals("byte " + i + " of shared prefix",
                    ack[i], denied[i]);
        }
    }

    @Test
    public void totalAckSizeIsTwentyFourBytes() throws Exception {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   17 (body) = 24 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        setTxn(pl, 0);
        assertEquals(24, datagramBytes(
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
    public void txnIncrementsAcrossInstances() throws Exception {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        setTxn(pl, 0);

        byte[] b1 = extractInnerBody(datagramBytes(
                new InventoryMoveAck(pl, 0, 0, 0, 0)), 17);
        byte[] b2 = extractInnerBody(datagramBytes(
                new InventoryMoveAck(pl, 0, 0, 0, 0)), 17);

        // txn at body offset 5..6 LE16
        int txn1 = (b1[5] & 0xFF) | ((b1[6] & 0xFF) << 8);
        int txn2 = (b2[5] & 0xFF) | ((b2[6] & 0xFF) << 8);
        assertEquals("first txn = 1 (initial 0 incremented)",
                1, txn1);
        assertEquals("second txn = 2",
                2, txn2);
    }
}
