package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link DamageEvent}.
 *
 * <p>Wire path: {@code 0x13 → 0x03 → 0x1f → [mapId LE2] →
 * 0x25 0x06 → 25-byte combat payload}. 27 bytes inner body.
 *
 * <p>Wire format:
 *
 * <pre>
 *   [0..1]   mapId LE2
 *   [2]      0x25            combat opcode
 *   [3]      0x06            damage sub-opcode
 *   [4]      0x01            flags
 *   [5]      0x05            sub-flags
 *   [6]      dmg_type        (0x00 physical / 0x0a energy)
 *   [7]      0x00            padding
 *   [8..9]   target_id LE2
 *   [10]     0x01            hit flag
 *   [11..14] damage float LE4
 *   [15..16] target_id LE2   (repeat)
 *   [17..18] attacker_id LE2
 *   [19..20] 0x00 0x00       padding
 *   [21..22] 0x73 0x06       (constant unknown field)
 *   [23..24] 0x00 0x00       padding
 *   [25..26] 0x20 0x41       (constant trailing field)
 * </pre>
 */
public class DamageEventByteIdentityTest {

    private static byte[] datagramBytes(DamageEvent pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Body starts at offset 11 (PacketBuilderUDP1303 frame). */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x1f", 0x1f, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void energyDamageBodyLayout() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0xCAFE);

        // damage = 100.0f → 0x42c80000 LE = 00 00 c8 42
        byte[] body = extractInnerBody(datagramBytes(
                new DamageEvent(pl, 100.0f,
                        0x1234,        // attackerId
                        0x0a)),        // dmg_type = energy
                27);

        // [0..1] mapId LE16 = 0xCAFE
        assertEquals(0xFE, body[0] & 0xFF);
        assertEquals(0xCA, body[1] & 0xFF);
        // [2..3] 25 06 combat sub-opcode
        assertEquals(0x25, body[2] & 0xFF);
        assertEquals(0x06, body[3] & 0xFF);
        // [4..5] 01 05 flags
        assertEquals(0x01, body[4] & 0xFF);
        assertEquals(0x05, body[5] & 0xFF);
        // [6] dmg_type
        assertEquals(0x0a, body[6] & 0xFF);
        // [7] padding
        assertEquals(0x00, body[7] & 0xFF);
        // [8..9] target_id (= mapId & 0xFFFF = 0xCAFE)
        assertEquals(0xFE, body[8] & 0xFF);
        assertEquals(0xCA, body[9] & 0xFF);
        // [10] hit flag
        assertEquals(0x01, body[10] & 0xFF);
        // [11..14] damage float LE32 = 100.0f = 0x42c80000
        assertEquals(0x00, body[11] & 0xFF);
        assertEquals(0x00, body[12] & 0xFF);
        assertEquals(0xc8, body[13] & 0xFF);
        assertEquals(0x42, body[14] & 0xFF);
        // [15..16] target_id repeat
        assertEquals(0xFE, body[15] & 0xFF);
        assertEquals(0xCA, body[16] & 0xFF);
        // [17..18] attacker_id LE16 = 0x1234
        assertEquals(0x34, body[17] & 0xFF);
        assertEquals(0x12, body[18] & 0xFF);
        // [19..20] padding
        assertEquals(0x00, body[19] & 0xFF);
        assertEquals(0x00, body[20] & 0xFF);
        // [21..22] constant 0x0673 LE
        assertEquals(0x73, body[21] & 0xFF);
        assertEquals(0x06, body[22] & 0xFF);
        // [23..24] padding
        assertEquals(0x00, body[23] & 0xFF);
        assertEquals(0x00, body[24] & 0xFF);
        // [25..26] constant 0x4120 LE
        assertEquals(0x20, body[25] & 0xFF);
        assertEquals(0x41, body[26] & 0xFF);
    }

    @Test
    public void physicalDamageEncodesDmgType00() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new DamageEvent(pl, 1.0f, 0, 0x00)), 27);
        assertEquals("dmg_type byte at offset 6",
                0x00, body[6] & 0xFF);
    }

    @Test
    public void damageFloatRoundTripsViaIEEE754() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        // 278.0f → 0x438b0000 LE = 00 00 8b 43
        byte[] body = extractInnerBody(datagramBytes(
                new DamageEvent(pl, 278.0f, 0, 0)), 27);

        int rawIntLE = (body[11] & 0xFF)
                | ((body[12] & 0xFF) << 8)
                | ((body[13] & 0xFF) << 16)
                | ((body[14] & 0xFF) << 24);
        assertEquals("damage float decodes back to 278.0f",
                278.0f, Float.intBitsToFloat(rawIntLE), 0.0f);
    }

    @Test
    public void targetIdEqualsPlayerMapId() {
        // The emitter explicitly uses pl.getMapID() & 0xFFFF as
        // the target entity (NOT the DB MISC_ID). Pin this so a
        // future refactor doesn't accidentally swap them — that
        // bug previously made damage bubbles appear at NPC
        // positions instead of the player.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0xABCD);
        byte[] body = extractInnerBody(datagramBytes(
                new DamageEvent(pl, 0.0f, 0, 0)), 27);
        // target appears at body offset 8..9 AND 15..16 (twice).
        assertEquals(0xCD, body[8]  & 0xFF);
        assertEquals(0xAB, body[9]  & 0xFF);
        assertEquals(0xCD, body[15] & 0xFF);
        assertEquals(0xAB, body[16] & 0xFF);
    }

    @Test
    public void totalDatagramSizeIsThirtyEightBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x1f) + 27 (body) = 38 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(38, datagramBytes(
                new DamageEvent(pl, 0, 0, 0)).length);
    }
}
