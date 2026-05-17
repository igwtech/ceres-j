package server.gameserver.packets;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.client_udp.RequestPositionUpdate;
import server.gameserver.packets.server_udp.CharInfo;
import server.gameserver.packets.server_udp.InfoResponse;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.PositionUpdate;
import server.interfaces.GameServerEvent;
import server.interfaces.ServerUDPPacket;
import server.testtools.CapturingUDPConnection;

/**
 * Functional dispatch round-trip for the C→S {@code 0x13 → 0x2a}
 * RequestPos packet.
 *
 * <p>Drives a verbatim retail 0x2a sub-packet body through the
 * real {@code GamePacketReaderUDP.decodesub13} sub-packet
 * dispatcher (the same code path {@code readPacket} uses for every
 * sub-packet split out of a 0x13 burst) and asserts:
 * <ol>
 *   <li>it routes to {@link RequestPositionUpdate},</li>
 *   <li>the decoded fields match the retail bytes, and</li>
 *   <li>{@code execute()} emits the retail refresh triple
 *       (PositionUpdate → CharInfo → InfoResponse zoneInfo).</li>
 * </ol>
 */
public class RequestPositionUpdateDispatchTest {

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(
                    s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static GameServerEvent dispatch(byte[] subPacket)
            throws Exception {
        Method m = GamePacketReaderUDP.class.getDeclaredMethod(
                "decodesub13", byte[].class);
        m.setAccessible(true);
        return (GameServerEvent) m.invoke(null, (Object) subPacket);
    }

    @Test
    public void retail16ByteBodyRoutesToRequestPositionUpdate()
            throws Exception {
        // RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715 — uid 0x0000a2b0.
        byte[] sub = hex("2ab0a200009484f3511dd2a1fa150c00");
        GameServerEvent ev = dispatch(sub);

        assertTrue("0x2a sub-packet must route to "
                + "RequestPositionUpdate, got "
                + (ev == null ? "null"
                        : ev.getClass().getSimpleName()),
                ev instanceof RequestPositionUpdate);

        RequestPositionUpdate p = (RequestPositionUpdate) ev;
        assertEquals(0x0000a2b0L, p.getCharacterUid());
        assertArrayEquals(hex("9484f3511dd2a1fa150c00"),
                p.getRequestToken());
    }

    @Test
    public void retail5ByteHeaderOnlyRoutesAndDecodes()
            throws Exception {
        byte[] sub = hex("2a1a7f0100");
        GameServerEvent ev = dispatch(sub);

        assertTrue(ev instanceof RequestPositionUpdate);
        RequestPositionUpdate p = (RequestPositionUpdate) ev;
        assertEquals(0x00017f1aL, p.getCharacterUid());
        assertNull(p.getRequestToken());
    }

    @Test
    public void executeEmitsRetailRefreshTriple() throws Exception {
        Player pl = PacketTestFixture.newPlayerWithZone();
        CapturingUDPConnection cap = new CapturingUDPConnection(
                java.net.InetAddress.getByName("127.0.0.1"),
                5000, pl);
        pl.setUdpConnection(cap);

        // Real retail body (ZONING_AND_ITEMS, uid 0x00017ebd).
        GameServerEvent ev =
                dispatch(hex("2abd7e010005ae73323a76d49d0e0900"));
        ((RequestPositionUpdate) ev).execute(pl);

        java.util.List<ServerUDPPacket> emitted = cap.received();
        assertTrue("execute() emits ≥3 UDP packets, got "
                + emitted.size(), emitted.size() >= 3);
        assertTrue(emitted.get(0) instanceof PositionUpdate);
        assertTrue(emitted.get(1) instanceof CharInfo);
        assertTrue(emitted.get(2) instanceof InfoResponse);
    }

    @Test
    public void nullPlayerExecuteDoesNotThrow() throws Exception {
        // The framing-artifact body must survive dispatch + execute
        // even with no player attached.
        GameServerEvent ev = dispatch(hex("2a431f"));
        assertTrue(ev instanceof RequestPositionUpdate);
        ((RequestPositionUpdate) ev).execute((Player) null);
        // Pass = no exception.
    }
}
