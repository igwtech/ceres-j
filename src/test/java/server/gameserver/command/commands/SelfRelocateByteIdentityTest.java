package server.gameserver.command.commands;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.PlayerPositionUpdate;
import server.gameserver.packets.server_udp.PositionUpdate;
import server.interfaces.ServerUDPPacket;

/**
 * Byte-identity test for the same-zone GM relocation packet pair
 * emitted by {@code tp}/{@code resetpos} (task #182).
 *
 * <p>Asserts {@link SelfRelocate#build(Player)} produces, in order,
 * exactly the two retail-validated self-position packets:
 *
 * <ol>
 *   <li>{@link PositionUpdate} — {@code 0x03/0x2c} StartPos. Byte
 *       layout already pinned to 4 retail pcaps by
 *       {@code PositionUpdateByteIdentityTest}; here we additionally
 *       assert it carries the <em>post-teleport</em> coordinates as
 *       IEEE-754 floats at body offsets 7/11/15 (Y/Z/X) so the
 *       client actually warps to the GM-set position.</li>
 *   <li>{@link PlayerPositionUpdate} — {@code 0x03/0x1b} variant A
 *       (12-byte, marker {@code 0x20}) watchdog echo, byte-identical
 *       to a standalone instance.</li>
 * </ol>
 *
 * <p>There is no retail capture of an intra-zone GM teleport, so the
 * retail-correctness of this pair is inherited transitively from the
 * world-entry path that emits the identical two packets and is itself
 * pcap-validated (see {@link SelfRelocate} javadoc).
 */
public class SelfRelocateByteIdentityTest {

    private static byte[] datagram(ServerUDPPacket pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Body starts after 0x13 outer 7B + 0x03 1B + seq 2B = 10B. */
    private static byte[] body(byte[] datagram, int len) {
        assertTrue("datagram too short", datagram.length >= 10 + len);
        assertEquals("outer 0x13", 0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03", 0x03, datagram[7] & 0xFF);
        byte[] b = new byte[len];
        System.arraycopy(datagram, 10, b, 0, len);
        return b;
    }

    @Test
    public void buildEmitsStartPosThen1bInThatOrder() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        ServerUDPPacket[] pkts = SelfRelocate.build(pl);
        assertEquals("two-packet relocation pair", 2, pkts.length);
        assertTrue("first is StartPos 0x2c",
                pkts[0] instanceof PositionUpdate);
        assertTrue("second is 0x1b self-pos",
                pkts[1] instanceof PlayerPositionUpdate);

        assertEquals("StartPos body[0] sub-opcode 0x2c",
                0x2c, body(datagram(pkts[0]), 1)[0] & 0xFF);
        byte[] b1b = body(datagram(pkts[1]), 12);
        assertEquals("0x1b body[0] sub-opcode", 0x1b,
                b1b[0] & 0xFF);
        // PlayerPositionUpdate writes (incl. leading 0x1b at b1b[0]):
        // 0x1b, mapID LE16 [1..2], 0x00 [3], 0x00 [4],
        // 0x20 marker [5], pad [6..8], 0xff [10], 0x14 [11].
        assertEquals("0x1b variant-A marker 0x20 at body[5]",
                0x20, b1b[5] & 0xFF);
    }

    @Test
    public void startPosCarriesPostTeleportCoordinates() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        PlayerCharacter pc = pl.getCharacter();
        // Simulate what TpCommand.moveTo() does before relocating.
        pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, 1234);
        pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, 5678);
        pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, 9012);

        ServerUDPPacket[] pkts = SelfRelocate.build(pl);
        byte[] b = body(datagram(pkts[0]), 19);
        ByteBuffer bb = ByteBuffer.wrap(b)
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("Y float at body[7..10]",
                5678.0f, bb.getFloat(7), 0.001f);
        assertEquals("Z float at body[11..14]",
                9012.0f, bb.getFloat(11), 0.001f);
        assertEquals("X float at body[15..18]",
                1234.0f, bb.getFloat(15), 0.001f);
    }

    @Test
    public void relocation1bIsByteIdenticalToStandalone1b() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        PlayerCharacter pc = pl.getCharacter();
        // First built packet bumps the reliable seq; build the
        // standalone reference from a fresh fixture at the same
        // seq state so the comparison is on the body, not the
        // session-counter wrapper.
        Player ref = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        // The reliable seq is assigned at constructor time, and
        // build() constructs StartPos before the 0x1b. Mirror that
        // ordering on the reference player so the 0x1b wrapper seq
        // aligns, then compare bodies.
        new PositionUpdate(ref);
        byte[] expected = body(datagram(new PlayerPositionUpdate(
                ref, ref.getCharacter(), ref.getMapID())), 12);

        ServerUDPPacket[] pkts = SelfRelocate.build(pl);
        // build() constructs StartPos then 0x1b; constructing
        // StartPos already advanced pl's seq exactly like ref.
        byte[] actual = body(datagram(pkts[1]), 12);
        assertArrayEquals("0x1b body must match standalone",
                expected, actual);
        // sanity: pc still attached / used
        assertNotNull(pc);
    }
}
