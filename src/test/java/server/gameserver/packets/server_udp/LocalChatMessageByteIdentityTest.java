package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link LocalChatMessage}.
 *
 * <p>Wire path: {@code 0x13 → 0x03 → 0x1f → [mapId LE2] → 0x1b
 * → ASCII message}. The {@code 0x1b} sub-tag (LocalChat) is the
 * client's broadcast-chat carrier — it routes the message to
 * the chat tab as a "local channel" line.
 */
public class LocalChatMessageByteIdentityTest {

    private static byte[] datagramBytes(LocalChatMessage pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Body starts at offset 11 (PacketBuilderUDP1303). */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x1f", 0x1f, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void bodyLayoutMapIdSubTagAndAscii() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        String msg = "hello";

        byte[] body = extractInnerBody(
                datagramBytes(new LocalChatMessage(pl, msg, 0xABCD)),
                3 + msg.length());
        // [0..1] mapId LE16
        assertEquals(0xCD, body[0] & 0xFF);
        assertEquals(0xAB, body[1] & 0xFF);
        // [2] sub-tag 0x1b
        assertEquals(0x1b, body[2] & 0xFF);
        // [3..7] message bytes
        byte[] expectedMsg = msg.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < expectedMsg.length; i++) {
            assertEquals("msg byte " + i,
                    expectedMsg[i], body[3 + i]);
        }
    }

    @Test
    public void noTrailingNullTerminator() {
        // Local chat messages don't carry a null terminator
        // (verified by source — write(message.getBytes()) only).
        // Pinning this so a future "always null-terminate" mutation
        // gets caught.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] datagram = datagramBytes(
                new LocalChatMessage(pl, "abc", 1));
        // Last byte should be 'c' (0x63), not 0x00.
        assertEquals(0x63, datagram[datagram.length - 1] & 0xFF);
    }

    @Test
    public void shortConstructorWritesZeroMapId() {
        // The 2-arg constructor writes [0x00 0x00] for mapId
        // (legacy behaviour). This test pins it so refactors
        // that route the player's own mapId through don't
        // surprise existing call sites.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(42);

        byte[] body = extractInnerBody(
                datagramBytes(new LocalChatMessage(pl, "x")), 4);
        assertEquals("mapId lo must be 0 (legacy)",
                0x00, body[0] & 0xFF);
        assertEquals("mapId hi must be 0 (legacy)",
                0x00, body[1] & 0xFF);
        assertEquals("sub-tag 0x1b",
                0x1b, body[2] & 0xFF);
        assertEquals("msg byte 'x'",
                'x',  body[3] & 0xFF);
    }

    @Test
    public void emptyMessageProducesThreeByteBody() {
        // Edge case: empty message → 3-byte body [mapId LE2][0x1b]
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new LocalChatMessage(pl, "", 1)), 3);
        assertEquals(0x01, body[0] & 0xFF);
        assertEquals(0x00, body[1] & 0xFF);
        assertEquals(0x1b, body[2] & 0xFF);
    }

    @Test
    public void unicodeMessageEncodesAsRawBytes() {
        // String.getBytes() uses the platform default charset.
        // For pure-ASCII messages this is fine. Pin the ASCII
        // case explicitly. A test for non-ASCII would be brittle
        // across JVM defaults.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        String msg = "Hello, World!";
        byte[] body = extractInnerBody(
                datagramBytes(new LocalChatMessage(pl, msg, 1)),
                3 + msg.length());
        byte[] expected = msg.getBytes();
        for (int i = 0; i < expected.length; i++) {
            assertEquals("byte " + i,
                    expected[i], body[3 + i]);
        }
    }
}
