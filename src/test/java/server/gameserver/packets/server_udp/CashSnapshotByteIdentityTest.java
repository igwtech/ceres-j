package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link CashSnapshot} — the 5-LE32
 * cash inventory snapshot (raw {@code 0x1f → 0x25 0x19}).
 *
 * <p>Captured from a 2026-04-26 retail vendor-buy. 25-byte
 * body: 5-byte sub-opcode prefix {@code 1f 01 00 25 19}
 * followed by five LE32 fields. Slot 0 is the wallet; the
 * remaining four are likely bank/safe/locker balances.
 */
public class CashSnapshotByteIdentityTest {

    private static byte[] datagramBytes(CashSnapshot pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP13 only): body starts at offset 7. */
    private static byte[] extractInnerBody(byte[] datagram) {
        assertEquals("outer 0x13", 0x13, datagram[0] & 0xFF);
        byte[] body = new byte[25];
        System.arraycopy(datagram, 7, body, 0, 25);
        return body;
    }

    @Test
    public void retailLikeBytesForKnownWallet() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        // wallet = 0x12345678, others = 0
        byte[] body = extractInnerBody(datagramBytes(
                new CashSnapshot(pl, 0x12345678)));

        // [0..4] sub-opcode prefix `1f 01 00 25 19`
        assertEquals(0x1f, body[0] & 0xFF);
        assertEquals(0x01, body[1] & 0xFF);
        assertEquals(0x00, body[2] & 0xFF);
        assertEquals(0x25, body[3] & 0xFF);
        assertEquals(0x19, body[4] & 0xFF);
        // [5..8] wallet LE32 = 0x12345678
        assertEquals(0x78, body[5] & 0xFF);
        assertEquals(0x56, body[6] & 0xFF);
        assertEquals(0x34, body[7] & 0xFF);
        assertEquals(0x12, body[8] & 0xFF);
        // [9..24] remaining 4 fields all zero
        for (int i = 9; i < 25; i++) {
            assertEquals("zero byte at offset " + i,
                    0x00, body[i] & 0xFF);
        }
    }

    @Test
    public void allFiveFieldsEncodeIndependently() {
        // Distinct values per slot to catch a swap.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new CashSnapshot(pl,
                        0xAABBCCDD,
                        0x11223344,
                        0x55667788,
                        (int) 0xDEADBEEFL,
                        0x01020304)));

        // wallet LE32 at body[5..8]
        assertEquals(0xDD, body[5]  & 0xFF);
        assertEquals(0xCC, body[6]  & 0xFF);
        assertEquals(0xBB, body[7]  & 0xFF);
        assertEquals(0xAA, body[8]  & 0xFF);
        // field1 at body[9..12]
        assertEquals(0x44, body[9]  & 0xFF);
        assertEquals(0x33, body[10] & 0xFF);
        assertEquals(0x22, body[11] & 0xFF);
        assertEquals(0x11, body[12] & 0xFF);
        // field2 at body[13..16]
        assertEquals(0x88, body[13] & 0xFF);
        assertEquals(0x77, body[14] & 0xFF);
        assertEquals(0x66, body[15] & 0xFF);
        assertEquals(0x55, body[16] & 0xFF);
        // field3 at body[17..20]
        assertEquals(0xEF, body[17] & 0xFF);
        assertEquals(0xBE, body[18] & 0xFF);
        assertEquals(0xAD, body[19] & 0xFF);
        assertEquals(0xDE, body[20] & 0xFF);
        // field4 at body[21..24]
        assertEquals(0x04, body[21] & 0xFF);
        assertEquals(0x03, body[22] & 0xFF);
        assertEquals(0x02, body[23] & 0xFF);
        assertEquals(0x01, body[24] & 0xFF);
    }

    @Test
    public void totalDatagramSizeIsThirtyTwoBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   25 (body) = 32 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(32, datagramBytes(new CashSnapshot(pl, 0)).length);
    }

    @Test
    public void singleArgConstructorZeroesOtherFields() {
        // The 2-arg constructor is for cases where Ceres-J only
        // models the wallet; bank/safe/locker default to 0.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new CashSnapshot(pl, 100)));

        // wallet at body[5..8] = 100 = 0x64
        assertEquals(0x64, body[5] & 0xFF);
        assertEquals(0x00, body[6] & 0xFF);
        // All four other LE32 slots zeroed (16 bytes from offset 9).
        for (int i = 9; i < 25; i++) {
            assertEquals("zero byte at offset " + i,
                    0x00, body[i] & 0xFF);
        }
    }

    @Test
    public void prefixIs0x25_0x19() {
        // 0x25/0x19 is the sub-opcode that distinguishes
        // CashSnapshot from sibling 0x25-family events
        // (0x25/0x13 = CashUpdate, 0x25/0x06 = DamageEvent,
        // 0x25/0x23 = DamageTick).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new CashSnapshot(pl, 0)));
        assertEquals("sub-opcode 0x25 at offset 3",
                0x25, body[3] & 0xFF);
        assertEquals("sub-sub-opcode 0x19 at offset 4",
                0x19, body[4] & 0xFF);
    }
}
