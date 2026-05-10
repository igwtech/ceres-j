package server.gameserver.packets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

import server.gameserver.packets.client_udp.Sub0x00Recognized;
import server.interfaces.GameServerEvent;

/**
 * Pin the dispatch behavior for 0x00 outer (unreliable channel)
 * sub-packets. Per {@code udp_c2s_00.md}, the 0x00 outer mirrors
 * the 0x03 reliable's sub-tag namespace (channel-duality) but
 * body layouts may differ per sub-tag (e.g. 0x00/0x27 uses LE16
 * world_id vs 0x03/0x27's LE32).
 *
 * <p>Current behavior: dispatcher routes 0x00 to
 * {@link Sub0x00Recognized} — a no-op consumer that prevents the
 * {@link UnknownClientUDPPacket} log spam (would otherwise hit
 * ~373/capture during PvP per the udp_c2s_00.md catalog evidence).
 *
 * <p>This test pins the recognition so a future refactor doesn't
 * accidentally drop the 0x00 outer back into the Unknown path.
 * The next step toward closing the P2 gap is per-sub-tag
 * unreliable handlers — they'd live downstream of the
 * {@link Sub0x00Recognized} route.
 */
public class GamePacketReaderUDPUnreliableTest {

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s+", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static GameServerEvent decode(byte[] subPacket) throws Exception {
        Method m = GamePacketReaderUDP.class.getDeclaredMethod(
                "decodesub13", byte[].class);
        m.setAccessible(true);
        return (GameServerEvent) m.invoke(null, (Object) subPacket);
    }

    private static void assertRecognised(byte[] sub, String label)
            throws Exception {
        GameServerEvent ev = decode(sub);
        assertNotNull(label + ": must return an event (not null)",
                ev);
        assertTrue(label + ": must route to Sub0x00Recognized, "
                + "not UnknownClientUDPPacket — got "
                + ev.getClass().getSimpleName(),
                ev instanceof Sub0x00Recognized);
    }

    @Test
    public void unreliable_0x00_0x2d_isRecognised() throws Exception {
        // 5,860 retail samples — dominant 6B PvP form.
        // Inner: [00][2d][sub-action][LE32 zero pad]
        assertRecognised(hex("00 2d 02 00 00 00"),
                "0x00/0x2d unreliable session-state (udp_c2s_00.md)");
    }

    @Test
    public void unreliable_0x00_0x3c_isRecognised() throws Exception {
        // 350 retail samples — entity-action unreliable form.
        // Inner: [00][3c][01 00][sub-action][LE32 ref][00][LE32 float]
        assertRecognised(
                hex("00 3c 01 00 03 47 00 00 00 00 62 ad 46"),
                "0x00/0x3c entity-action (udp_c2s_3c equivalent)");
    }

    @Test
    public void unreliable_0x00_0x27_isRecognised() throws Exception {
        // 60 retail samples — RequestInfo unreliable form.
        // Wire: [00][27][LE16 world_id][pad]
        assertRecognised(hex("00 27 fb 03 00"),
                "0x00/0x27 RequestInfo unreliable (subway probe)");
    }

    @Test
    public void unreliable_bare_0x00_isRecognised() throws Exception {
        // 23 retail samples — 1-byte keepalive ping.
        assertRecognised(hex("00"),
                "bare 0x00 keepalive ping");
    }

    @Test
    public void reliable_0x03_stillRecognisesKnownSubTags()
            throws Exception {
        // Regression guard: 0x00 recognition must not affect the
        // existing 0x03 reliable dispatch.
        // Sample: 0x03/0x1f/0x17 UseItem (a routine reliable shape).
        byte[] sub = hex("03 01 00 1f 00 00 17 00 00 00");
        GameServerEvent ev = decode(sub);
        assertNotNull(ev);
        assertEquals("0x03 reliable must still route to UseItem, "
                + "not be deflected by the 0x00 case",
                "UseItem", ev.getClass().getSimpleName());
    }
}
