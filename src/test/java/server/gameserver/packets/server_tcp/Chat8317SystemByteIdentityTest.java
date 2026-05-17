package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Byte-identity test for the {@link Chat8317#system(String, String)}
 * server / system-broadcast form — the packet
 * {@link server.gameserver.AdminCommandHandler#reply} now uses so GM
 * command replies actually render in the retail client's chat window.
 *
 * <p>Pinned against {@code RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715}
 * (the only retail capture containing a server broadcast). The decoded
 * sample, documented in {@code docs/protocol/packets/tcp_s2c_8317.md}:
 *
 * <pre>
 * 83 17  ff ff ff ff  0c  ff  00  "Server Admin"  &lt;msg&gt;
 *         ^system uid       ^chnl=0xff
 * </pre>
 *
 * <p>The legacy hand-rolled {@code LocalChatMessage}
 * ({@code 0x1f [map] 0x1b} with no length field / no terminator) was
 * never rendered by the retail client — that is the root cause of
 * reply-only commands like {@code .help} appearing to do nothing.
 */
public class Chat8317SystemByteIdentityTest {

    /** Strip FE+LE2 framing to get the body. */
    private static byte[] body(byte[] framed) {
        int len = (framed[1] & 0xff) | ((framed[2] & 0xff) << 8);
        byte[] out = new byte[len];
        System.arraycopy(framed, 3, out, 0, len);
        return out;
    }

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s+", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    /**
     * Exact retail prefix from VEHICLE_DRONE: system uid, name "Server
     * Admin" (len 0x0c), channel 0xff, sub-channel 0x00. We pin the
     * full header + name; the broadcast message text in that capture
     * is an NCPD surveillance bulletin we substitute deterministically.
     */
    @Test
    public void retailSystemBroadcastHeaderMatches() {
        byte[] retailHeader = hex(
                "83 17" +
                "ff ff ff ff" +          // system sender uid
                "0c" +                   // name_len = 12
                "ff" +                   // channel = 0xff (system)
                "00" +                   // sub-channel
                "53 65 72 76 65 72 20 41 64 6d 69 6e"); // "Server Admin"

        byte[] mine = body(
                Chat8317.system("Server Admin", "NCPD bulletin")
                        .getData());

        byte[] minePrefix = new byte[retailHeader.length];
        System.arraycopy(mine, 0, minePrefix, 0, retailHeader.length);
        assertArrayEquals("retail system-broadcast header byte-equal",
                retailHeader, minePrefix);

        // Message bytes follow the 21-byte header verbatim.
        assertEquals("NCPD bulletin",
                new String(mine, retailHeader.length,
                        mine.length - retailHeader.length));
    }

    /**
     * Full-packet byte identity for the exact sender name "Server"
     * that {@link server.gameserver.AdminCommandHandler#reply} uses,
     * with the "[Server] " prefix it prepends.
     */
    @Test
    public void replyShapeIsByteExact() {
        byte[] expected = hex(
                "83 17" +
                "ff ff ff ff" +
                "06" +                       // name_len = 6 ("Server")
                "ff" +
                "00" +
                "53 65 72 76 65 72" +        // "Server"
                // "[Server] hi" message
                "5b 53 65 72 76 65 72 5d 20 68 69");
        byte[] mine = body(
                Chat8317.system("Server", "[Server] hi").getData());
        assertArrayEquals("reply packet byte-equal", expected, mine);
    }

    @Test
    public void systemConstantsAreRetailValues() {
        assertEquals(0xffffffff, Chat8317.SYSTEM_UID);
        assertEquals(0xff, Chat8317.CHANNEL_SYSTEM);
        // Local "say" channel = 0x04 per tcp_s2c_8317.md.
        assertEquals((byte) 0x04, Chat8317.CHANNEL_LOCAL);
    }
}
