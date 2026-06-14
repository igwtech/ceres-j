package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link NpcKillCredit} — the {@code 0x03/0x2d}
 * NPC death / kill-credit gate.
 *
 * <p>Wire path: {@code 0x13 -> 0x03 -> 0x2d -> 11B body}, total
 * 12-byte inner. Retail-pinned 2026-06-13 from a live NPC kill
 * (Norman Gates, killer entity {@code 0x0001}, kills "Reggie"
 * entity {@code 0x012c}): {@code 2d 2c01 0000 01 0100 0000 <dmg> 00}.
 * See memory {@code combat_loop_retail_kill_pinned}.
 */
public class NpcKillCreditByteIdentityTest {

    private static byte[] datagramBytes(NpcKillCredit pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Inner body (incl. the 0x2d sub-op) starts at wire offset 10:
     *  0x13(1)+ctr(2)+ctr+sk(2)+subLen(2)+0x03(1)+seq(2) = 10. */
    private static byte[] innerBody(byte[] datagram) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x2d", 0x2d, datagram[10] & 0xFF);
        byte[] body = new byte[datagram.length - 10];
        System.arraycopy(datagram, 10, body, 0, body.length);
        return body;
    }

    @Test
    public void retailReggieKillIsByteIdentical() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        // dead=Reggie 0x012c, killer=Norman 0x0001, dmg=0x52 (82).
        byte[] body = innerBody(datagramBytes(
                new NpcKillCredit(pl, 0x012c, 0x0001, 0x52)));

        assertArrayEquals(
            new byte[] {
                0x2d,              // [0]   sub-opcode
                0x2c, 0x01,        // [1-2] dead entity LE16 (0x012c)
                0x00, 0x00,        // [3-4] CONSTANT
                0x01,              // [5]   kill/credit form discriminator
                0x01, 0x00,        // [6-7] killer entity LE16 (0x0001)
                0x00, 0x00,        // [8-9] CONSTANT
                0x52,              // [10]  dmg (HP removed)
                0x00               // [11]  CONSTANT
            }, body);
    }

    @Test
    public void entityIdsEncodeLittleEndian() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        byte[] body = innerBody(datagramBytes(
                new NpcKillCredit(pl, 0xDEAD, 0xBEEF, 0x10)));
        assertEquals(0x2d, body[0] & 0xFF);
        assertEquals(0xAD, body[1] & 0xFF);   // dead lo
        assertEquals(0xDE, body[2] & 0xFF);   // dead hi
        assertEquals(0x01, body[5] & 0xFF);   // kill/credit form
        assertEquals(0xEF, body[6] & 0xFF);   // killer lo
        assertEquals(0xBE, body[7] & 0xFF);   // killer hi
        assertEquals(0x10, body[10] & 0xFF);  // dmg
    }

    @Test
    public void totalDatagramSizeIsTwentyTwoBytes() {
        // 10-byte reliable header + 12-byte body.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        assertEquals(22, datagramBytes(
                new NpcKillCredit(pl, 1, 2, 3)).length);
    }

    @Test
    public void dmgClampsIntoSingleByte() {
        // Mobs with effective HP > 255 saturate the 1-byte dmg field.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        byte[] body = innerBody(datagramBytes(
                new NpcKillCredit(pl, 1, 2, 300)));
        assertEquals(0xff, body[10] & 0xFF);

        byte[] neg = innerBody(datagramBytes(
                new NpcKillCredit(pl, 1, 2, -5)));
        assertEquals(0x00, neg[10] & 0xFF);
    }

    @Test
    public void clampDmgHelper() {
        assertEquals(0, NpcKillCredit.clampDmg(-1));
        assertEquals(0, NpcKillCredit.clampDmg(0));
        assertEquals(170, NpcKillCredit.clampDmg(170));
        assertEquals(255, NpcKillCredit.clampDmg(255));
        assertEquals(255, NpcKillCredit.clampDmg(1000));
    }
}
