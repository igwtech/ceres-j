package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.internalEvents.InitRequestFallback;
import server.gameserver.internalEvents.WorldEntryEvent;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.Event;
import server.testtools.CapturingUDPConnection;
import server.tools.PriorityList;

/**
 * Login world entry is pulled by the client's raw {@code 0x2a} init
 * request, like retail (retail_b862_1: {@code 0x2a} 59 ms after the
 * UDPAlive, first reliable ~0.2 s later). With the world pushed unasked
 * the client never sent {@code 0x2a} and kept map id 0 in its own
 * {@code 0x20}/{@code 0x1f}.
 */
public class InitRequestPullTest {

    private static List<Event> drainEvents(Player pl) throws Exception {
        java.lang.reflect.Field f = Player.class.getDeclaredField("eventList");
        f.setAccessible(true);
        PriorityList q = (PriorityList) f.get(pl);
        List<Event> out = new ArrayList<>();
        while (!q.isEmpty()) out.add(q.removeFirst());
        return out;
    }

    private static CapturingUDPConnection capture(Player pl) throws Exception {
        CapturingUDPConnection cap = new CapturingUDPConnection(
                java.net.InetAddress.getByName("127.0.0.1"), 5000, pl);
        pl.setUdpConnection(cap);
        return cap;
    }

    private static byte[] initRequest() {
        // retail form: 2a [account LE32] [11-byte token]
        return new byte[]{0x2a, 0x02, 0x00, 0x00, 0x00,
                0x5c, (byte) 0xd5, 0x36, (byte) 0xe6, (byte) 0xe0, (byte) 0xda,
                0x77, 0x6f, (byte) 0x8d, 0x0b, 0x00};
    }

    @Test
    public void initRequestStartsTheWorldEntryOnce() throws Exception {
        Player pl = PacketTestFixture.newPlayerWithZone();
        CapturingUDPConnection cap = capture(pl);
        pl.setAwaitingInitRequest(true);

        new RequestPositionUpdate(initRequest()).execute(pl);

        assertEquals("nothing is sent directly; the world entry streams it",
                0, cap.received().size());
        List<Event> ev = drainEvents(pl);
        assertEquals(1, ev.size());
        assertTrue(ev.get(0) instanceof WorldEntryEvent);
        assertFalse("flag consumed", pl.consumeAwaitingInitRequest());
    }

    @Test
    public void laterRequestKeepsTheResyncReply() throws Exception {
        Player pl = PacketTestFixture.newPlayerWithZone();
        CapturingUDPConnection cap = capture(pl);

        new RequestPositionUpdate(initRequest()).execute(pl);

        assertTrue(cap.received().size() >= 3);
        assertTrue(drainEvents(pl).isEmpty());
    }

    @Test
    public void fallbackStartsTheWorldEntryOnlyWhileStillWaiting()
            throws Exception {
        Player pl = PacketTestFixture.newPlayerWithZone();
        pl.setAwaitingInitRequest(true);
        new InitRequestFallback().execute(pl);
        List<Event> ev = drainEvents(pl);
        assertEquals(1, ev.size());
        assertTrue(ev.get(0) instanceof WorldEntryEvent);

        // The init request already started it: the timer does nothing.
        new InitRequestFallback().execute(pl);
        assertTrue(drainEvents(pl).isEmpty());
    }
}
