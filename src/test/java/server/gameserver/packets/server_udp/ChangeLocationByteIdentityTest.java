package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link ChangeLocation} — the zone-transition
 * portal message (UDP S→C reliable
 * {@code 0x03/0x1f → <localId> → 0x38}).
 *
 * <p>Pins the wire layout against the TinNS
 * {@code BuildChangeLocationMsg} reference
 * ({@code tinns/.../MessageBuilder.cxx:1351-1381}): the reliable
 * sub-packet body is
 *
 * <pre>
 *   1f &lt;localId LE16&gt; 38 04 &lt;entityType u8&gt;
 *      &lt;Location u32 LE&gt; &lt;Entity u16 LE&gt;
 * </pre>
 *
 * <p>Concrete §5 mapping pinned here:
 * {@code pepper_p3 worldmodel 380 → ft 18 → appplaces 130
 * "sewer entrance" → destWorld 946, Entity 1}. Because the
 * worldmodel functionType is 18 (NOT 20/29), the TinNS-derived
 * entityType byte is {@code 0} (the appplaces SewerLevel field of 4
 * is TinNS debug-only and is deliberately NOT on the wire — see
 * {@link server.gameserver.PortalResolver} javadoc).
 */
public class ChangeLocationByteIdentityTest {

    private static byte[] datagramBytes(ChangeLocation pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP1303):
     *  {@code [0x13][ctr LE2][ctr+sk LE2][size LE2][0x03][seq LE2]
     *  [body]}. Body (the 0x1f sub-packet) starts at offset 10. */
    private static byte[] extractInnerBody(byte[] datagram,
                                           int bodyLen) {
        assertEquals("outer 0x13",    0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03", 0x03, datagram[7] & 0xFF);
        byte[] body = new byte[bodyLen];
        System.arraycopy(datagram, 10, body, 0, bodyLen);
        return body;
    }

    @Test
    public void pepperP3Wm380BytesByteEqual() {
        // localId = pl.getMapID(); the fixture sets it to 1.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        assertEquals(1, pl.getMapID());

        // pepper_p3 wm 380 → world 946, Entity 1, ft 18 → entityType 0
        ChangeLocation pkt = new ChangeLocation(pl, 946, 1, 0);

        // body = 1f + localId LE16 + 38 + 04 + entityType
        //        + Location LE32 + Entity LE16  = 12 bytes
        byte[] body = extractInnerBody(datagramBytes(pkt), 12);
        byte[] expected = {
                0x1f,
                0x01, 0x00,                   // localId LE16 = 1
                0x38,
                0x04,                          // TinNS const "Accepted(?)"
                0x00,                          // entityType (ft 18 → 0)
                (byte) 0xb2, 0x03, 0x00, 0x00, // Location LE32 = 946
                0x01, 0x00                     // Entity LE16 = 1
        };
        assertArrayEquals(
                "pepper_p3 wm380 ChangeLocation must match the "
                + "TinNS BuildChangeLocationMsg layout",
                expected, body);
    }

    @Test
    public void datfileWorldchangeActorEntityTypeIsOne() {
        // §5 plaza_p1 wm 2018 ft 20 → appplaces 818 "Reactor Room
        // entrance" → world 1573, Entity 1. ft==20 ⇒ entityType=1
        // (TinNS SewerLevel = (ft==20||ft==29)?1:0).
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        ChangeLocation pkt = new ChangeLocation(pl, 1573, 1, 1);
        byte[] body = extractInnerBody(datagramBytes(pkt), 12);
        byte[] expected = {
                0x1f,
                0x01, 0x00,
                0x38,
                0x04,
                0x01,                          // entityType (ft 20 → 1)
                (byte) 0x25, 0x06, 0x00, 0x00, // Location LE32 = 1573
                0x01, 0x00
        };
        assertArrayEquals(expected, body);
    }

    @Test
    public void localIdTracksGetMapID() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0x1234);
        byte[] body = extractInnerBody(
                datagramBytes(new ChangeLocation(pl, 1, 2, 0)), 12);
        assertEquals(0x1f, body[0] & 0xFF);
        assertEquals(0x34, body[1] & 0xFF); // localId lo
        assertEquals(0x12, body[2] & 0xFF); // localId hi
        assertEquals(0x38, body[3] & 0xFF);
    }

    @Test
    public void locationAndEntityAreLittleEndian() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new ChangeLocation(pl, 0x12345678, 0xABCD, 0)), 12);
        // Location LE32 at body offset 6..9
        assertEquals(0x78, body[6]  & 0xFF);
        assertEquals(0x56, body[7]  & 0xFF);
        assertEquals(0x34, body[8]  & 0xFF);
        assertEquals(0x12, body[9]  & 0xFF);
        // Entity LE16 at body offset 10..11
        assertEquals(0xCD, body[10] & 0xFF);
        assertEquals(0xAB, body[11] & 0xFF);
    }

    @Test
    public void totalDatagramSizeIsTwentyTwoBytes() {
        // 1 (0x13) + 2 (ctr) + 2 (ctr+sk) + 2 (size) + 1 (0x03)
        //   + 2 (seq) + 11 (body) = 21? — body is 12 bytes:
        // 1f + 2 + 1 + 1 + 1 + 4 + 2 = 12 → total 22.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        assertEquals(22, datagramBytes(
                new ChangeLocation(pl, 946, 1, 0)).length);
    }
}
