package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.GameServerUDPConnection;
import server.gameserver.Player;

/**
 * Byte-identity test for {@link EquipStateAck} — the toolbelt
 * EQUIP / HOLSTER S→C state-ack (UDP reliable
 * {@code 0x03/0x1f → 0x25 0x13 → 0x0b}).
 *
 * <p>Byte-pinned against retail (1:1 causal, two sessions): the
 * client's {@code 1f 01 00 1f <slot>} request yields exactly one
 * reliable S→C packet with inner body
 * {@code 1f [mapid LE2] 25 13 [txn LE2] 0b [slot] 00}.
 *
 * <p>This pins the 10-byte inner body so a refactor of the txn
 * counter or sub-tag encoding can't regress the wire bytes.
 */
public class EquipStateAckByteIdentityTest {

    /** Force the per-player state-ack txn counter so {@code nextStateAckTxn()}
     *  returns a predictable value. Counter is pre-increment, so setting it to
     *  {@code n} makes the next call return {@code n+1}. */
    private static void setTxn(Player pl, int value) throws Exception {
        Field f = GameServerUDPConnection.class.getDeclaredField("stateAckTxn");
        f.setAccessible(true);
        f.setInt(pl.getUdpConnection(), value);
    }

    private static byte[] datagramBytes(EquipStateAck pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP1303):
     *  {@code [0x13][counter LE2][counter+sk LE2][size LE2][0x03][seq LE2][0x1f][body...]}
     *  The inner body (from the {@code 0x1f} sub-opcode onward) starts at
     *  offset 10. Returns the 10-byte body
     *  {@code 1f [mapid LE2] 25 13 [txn LE2] 0b [slot] 00}. */
    private static byte[] extractInnerBody(byte[] datagram) {
        assertEquals("outer 0x13",    0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03", 0x03, datagram[7] & 0xFF);
        assertEquals("sub-op 0x1f",   0x1f, datagram[10] & 0xFF);
        byte[] body = new byte[10];
        System.arraycopy(datagram, 10, body, 0, 10);
        return body;
    }

    @Test
    public void holsterSlotZeroBytesByteEqual() throws Exception {
        // mapid = 1 (PacketTestFixture sets MapID=1, retail apartment).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        setTxn(pl, 0x2fe7);   // next txn → 0x2fe8

        byte[] body = extractInnerBody(datagramBytes(
                new EquipStateAck(pl, 0x00 /*holster*/)));
        byte[] expected = {
                0x1f,
                0x01, 0x00,            // mapid LE2 = 1
                0x25, 0x13,
                (byte) 0xe8, 0x2f,     // txn LE2 = 0x2fe8
                0x0b,                  // TAG_EQUIP_SLOT
                0x00,                  // slot = holster
                0x00                   // pad
        };
        assertArrayEquals("holster body must match pinned bytes",
                expected, body);
    }

    @Test
    public void equipSlotOneBytesByteEqual() throws Exception {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        setTxn(pl, 0x2fe8);   // next txn → 0x2fe9

        byte[] body = extractInnerBody(datagramBytes(
                new EquipStateAck(pl, 0x01)));
        byte[] expected = {
                0x1f,
                0x01, 0x00,            // mapid LE2 = 1
                0x25, 0x13,
                (byte) 0xe9, 0x2f,     // txn LE2 = 0x2fe9
                0x0b,                  // TAG_EQUIP_SLOT
                0x01,                  // slot 1
                0x00                   // pad
        };
        assertArrayEquals("slot-1 body must match pinned bytes",
                expected, body);
    }

    @Test
    public void mapIdEncodesLittleEndian() throws Exception {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0x1234);
        setTxn(pl, 0);

        byte[] body = extractInnerBody(datagramBytes(
                new EquipStateAck(pl, 0x04)));
        // mapid LE2 at body offset 1..2
        assertEquals(0x34, body[1] & 0xFF);
        assertEquals(0x12, body[2] & 0xFF);
        // slot echoed at offset 8
        assertEquals(0x04, body[8] & 0xFF);
    }

    @Test
    public void txnIncrementsPerEvent() throws Exception {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        setTxn(pl, 0);

        byte[] b1 = extractInnerBody(datagramBytes(new EquipStateAck(pl, 0)));
        byte[] b2 = extractInnerBody(datagramBytes(new EquipStateAck(pl, 0)));
        // txn lo byte at body offset 5
        assertEquals(1, b1[5] & 0xFF);
        assertEquals(2, b2[5] & 0xFF);
    }

    @Test
    public void subTagIs0x0b() {
        assertEquals(0x0b, EquipStateAck.TAG_EQUIP_SLOT);
    }

    @Test
    public void totalDatagramSizeIsTwentyOneBytes() throws Exception {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 10 (body incl. 0x1f) = 20 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        setTxn(pl, 0);
        assertEquals(20, datagramBytes(new EquipStateAck(pl, 0)).length);
    }
}
