package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link NetHostWorldName} — the
 * plaintext UDP {@code 0x83/0x0c} "world name" message
 * candidate that aims to advance the client's WorldClient
 * state machine past state 2 (block).
 *
 * <p>Wire format (raw UDP, no 0x13 frame):
 *
 * <pre>
 *   offset 0     : 0x83          NetHost msg marker
 *   offset 1     : 0x0c          sub-opcode "world name"
 *   offset 2..5  : posX  LE32
 *   offset 6..9  : posY  LE32
 *   offset 10..13: posZ  LE32
 *   offset 14..  : worldname (null-terminated)
 * </pre>
 *
 * <p>The fixture player's {@code Zone} is null (no world is
 * registered in tests), so the emitter falls through to the
 * {@code "plaza_p1"} default — pinning that fallback is part
 * of the contract.
 */
public class NetHostWorldNameByteIdentityTest {

    private static byte[] datagramBytes(NetHostWorldName pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    @Test
    public void headerBytesAreFixed() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] b = datagramBytes(new NetHostWorldName(pl));
        assertEquals("first byte 0x83 (NetHost marker)",
                0x83, b[0] & 0xFF);
        assertEquals("second byte 0x0c (sub-opcode world name)",
                0x0c, b[1] & 0xFF);
    }

    @Test
    public void positionFieldsAreThreeLE32Zeros() {
        // Emitter writes posX = posY = posZ = 0 (no meaningful
        // start position available). Pin the 12 zero bytes at
        // offsets 2..13.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] b = datagramBytes(new NetHostWorldName(pl));
        for (int i = 2; i < 14; i++) {
            assertEquals("position byte at offset " + i,
                    0x00, b[i] & 0xFF);
        }
    }

    @Test
    public void fallbackWorldNameIsPlazaP1() {
        // Fixture player has no Zone → emitter uses the
        // hardcoded fallback "plaza_p1". Pin that string at
        // offset 14, plus the trailing null at offset 22.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] b = datagramBytes(new NetHostWorldName(pl));

        byte[] expectedName = "plaza_p1"
                .getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < expectedName.length; i++) {
            assertEquals("name byte " + i + " ('" + (char) expectedName[i] + "')",
                    expectedName[i], b[14 + i]);
        }
        // Null terminator at offset 14 + 8 = 22
        assertEquals("trailing null terminator",
                0x00, b[14 + expectedName.length] & 0xFF);
    }

    @Test
    public void totalDatagramSizeIsTwentyThreeBytesForPlazaP1() {
        // 2 (opcode) + 12 (3× LE32) + 8 ("plaza_p1") + 1 (null) = 23 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(23, datagramBytes(
                new NetHostWorldName(pl)).length);
    }

    @Test
    public void noOuter0x13Frame() {
        // Unlike most server_udp packets, NetHostWorldName is
        // sent RAW — no 0x13 outer header. Pin the absence.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] b = datagramBytes(new NetHostWorldName(pl));
        assertNotEquals("first byte must NOT be 0x13 (this is "
                + "a raw NetHost packet, not a 0x13-framed gamedata "
                + "packet)",
                (byte) 0x13, b[0]);
    }
}
