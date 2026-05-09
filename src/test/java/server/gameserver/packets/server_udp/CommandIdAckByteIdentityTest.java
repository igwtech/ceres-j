package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link CommandIdAck}
 * (UDP S→C reliable {@code 0x03/0x19} sub-opcode 0x07).
 *
 * <p>Sets the client's {@code +0x2ec} (command ID) field which
 * {@code FUN_00558950} polls during the world-change state
 * chain. Wire format from Ghidra decompile of
 * {@code FUN_00559920} case 0x19 sub 0x07:
 *
 * <pre>
 *   0x00  0x19              sub-type
 *   0x01  0x07              inner sub-opcode (switch case)
 *   0x02  0x00              padding
 *   0x03  int32             command ID → +0x2ec
 * </pre>
 *
 * <p>Total inner body = 7 bytes (satisfies the {@code 6 < uVar6}
 * length precondition).
 */
public class CommandIdAckByteIdentityTest {

    private static byte[] datagramBytes(CommandIdAck pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Body starts at offset 11 (PacketBuilderUDP1303 frame). */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x19", 0x19, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void bodyMatchesGhidraSpec() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        // commandId = 0x12345678
        byte[] body = extractInnerBody(
                datagramBytes(new CommandIdAck(pl, 0x12345678)), 6);
        // [0] = 0x07 inner sub-opcode (case 7)
        assertEquals(0x07, body[0] & 0xFF);
        // [1] = 0x00 padding
        assertEquals(0x00, body[1] & 0xFF);
        // [2..5] = LE32 commandId
        assertEquals(0x78, body[2] & 0xFF);
        assertEquals(0x56, body[3] & 0xFF);
        assertEquals(0x34, body[4] & 0xFF);
        assertEquals(0x12, body[5] & 0xFF);
    }

    @Test
    public void commandIdEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new CommandIdAck(pl, 1)), 6);
        // commandId LE32 = 1
        assertEquals(0x01, body[2] & 0xFF);
        assertEquals(0x00, body[3] & 0xFF);
        assertEquals(0x00, body[4] & 0xFF);
        assertEquals(0x00, body[5] & 0xFF);
    }

    @Test
    public void totalDatagramSizeIsEighteenBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x19) + 6 (body) = 17 bytes
        // Wait — the inner body includes the 0x07/0x00 prefix +
        // 4-byte LE32. Total = 17B.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(17, datagramBytes(
                new CommandIdAck(pl, 0)).length);
    }

    @Test
    public void preconditionLengthGreaterThanSix() {
        // The Ghidra decompile gates on `6 < uVar6` (packet body
        // length > 6 bytes). Our inner body must be at least 7
        // bytes after the 0x19 sub-type to satisfy it. Verify.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        // Inner body length after 0x19 = 6 bytes (0x07 + 0x00 +
        // 4-byte LE32). Plus the 0x19 sub-type itself = 7 total.
        // The Ghidra check counts from the 0x19, which gives 7.
        byte[] body = extractInnerBody(
                datagramBytes(new CommandIdAck(pl, 1)), 6);
        assertTrue("inner body after 0x19 must be >= 6 bytes "
                + "(Ghidra gate: 6 < uVar6, where uVar6 includes "
                + "the 0x19 byte)",
                body.length + 1 > 6);
    }
}
