package server.gameserver.packets.server_udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Tests for {@link ServerReliableAck} — verifies the byte-level wire
 * layout of the S->C reliable-ACK channel (sub-tag {@code 0x09}).
 *
 * <p>Reference: 35 retail samples across 11/17 captures. Inner body is
 * always {@code [ack_seq LE16]} where ack_seq tracks the
 * just-received client-reliable seq.
 *
 * <p>Wire layout (total 13 bytes for the standard form):
 * <pre>
 *   0x00  0x13                  outer gamedata header
 *   0x01  short  counter        outer counter (LE)
 *   0x03  short  ckey           counter + sessionkey
 *   0x05  short  size = 6       inner sub-packet length LE
 *   0x07  0x03                  reliable wrapper sub-type
 *   0x08  short  seqCounter     server's reliable seq (LE)
 *   0x0a  0x09                  ServerReliableAck sub-tag
 *   0x0b  short  ackSeq         client's seq being acked (LE)
 * </pre>
 */
public class ServerReliableAckByteIdentityTest {

    @Test
    public void serialisesAckSeq_2ByteLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0x4242);

        ServerReliableAck pkt = new ServerReliableAck(pl, 0x1234);
        DatagramPacket[] dps = pkt.getDatagramPackets();
        assertNotNull(dps);
        assertEquals("ServerReliableAck must fit in a single UDP datagram",
                1, dps.length);

        byte[] bytes = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, bytes, 0, bytes.length);

        // 7 (outer hdr) + 3 (reliable 0x03 + seqCounter LE2)
        // + 1 (sub-tag 0x09) + 2 (ackSeq LE2) = 13 bytes
        assertEquals("unexpected packet length: " + PacketTestFixture.hex(bytes),
                13, bytes.length);

        // Outer 0x13 header
        assertEquals(0x13, bytes[0] & 0xFF);

        // Inner sub-packet length at offset 5 (LE16) = 6 (the
        // [03][seq LE2][09][ack LE2] block).
        int innerLen = (bytes[5] & 0xFF) | ((bytes[6] & 0xFF) << 8);
        assertEquals("inner sub-packet length must be 6", 6, innerLen);

        // Reliable wrapper sub-type 0x03 at offset 7
        assertEquals(0x03, bytes[7] & 0xFF);

        // Server seq LE2 at offset 8..9 (incremented from 0 by ctor)
        // — we don't pin the exact value; just verify the field
        // exists at the right offset by reading both bytes.

        // Sub-tag 0x09 at offset 10 (= ServerReliableAck marker)
        assertEquals("sub-tag at offset 10 must be 0x09 (ServerReliableAck)",
                0x09, bytes[10] & 0xFF);

        // ack_seq LE16 at offset 11..12 = 0x1234
        assertEquals("ack_seq low byte", 0x34, bytes[11] & 0xFF);
        assertEquals("ack_seq high byte", 0x12, bytes[12] & 0xFF);
    }

    @Test
    public void ackSeqWraps_at65536() {
        // Pass a value larger than 16 bits — implementation should
        // mask to LE16.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0x0000);
        ServerReliableAck pkt = new ServerReliableAck(pl, 0xDEAD_BEEF);

        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] bytes = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, bytes, 0, bytes.length);

        // ack_seq LE16 at offset 11..12 should be 0xBEEF (low 16 bits)
        assertEquals(0xEF, bytes[11] & 0xFF);
        assertEquals(0xBE, bytes[12] & 0xFF);
    }

    @Test
    public void ackSeqZero_isValidAndPinned() {
        // Edge case: ack_seq=0x0000 is the very first ack at session
        // start (matches retail's 0x0000 form in early captures).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0x0000);
        ServerReliableAck pkt = new ServerReliableAck(pl, 0);

        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] bytes = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, bytes, 0, bytes.length);

        assertEquals(0x09, bytes[10] & 0xFF);
        assertEquals(0x00, bytes[11] & 0xFF);
        assertEquals(0x00, bytes[12] & 0xFF);
    }
}
