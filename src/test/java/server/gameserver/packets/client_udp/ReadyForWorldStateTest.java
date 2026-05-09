package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.GameServerEvent;
import server.tools.PriorityList;

/**
 * Functional test for {@link ReadyForWorldState} — the C→S
 * {@code 0x03/0x24} "ready for world state" trigger. Without a
 * response the modern client stays stuck on the
 * "SYNCHRONIZING INTO CITY ZONE" overlay and aborts after ~26s.
 *
 * <p>The handler defers the response burst by 20ms (lets the
 * incoming packet's ACK leave the socket first) and then
 * emits ChatList + InfoResponse + GamePacketTimeSync +
 * LongPlayerInfo + PlayerPositionUpdate + zone population +
 * a closing GamePacketTimeSync.
 */
public class ReadyForWorldStateTest {

    /** Build a 6-byte body: {@code 03 [seq2] 24 [01 00]}. */
    private static byte[] buildBody() {
        return new byte[]{
                0x03, 0x42, 0x00,
                0x24, 0x01, 0x00
        };
    }

    @Test
    public void executeSchedulesWorldStatePopulateEvent()
            throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        Field f = Player.class.getDeclaredField("eventList");
        f.setAccessible(true);
        PriorityList queue = (PriorityList) f.get(pl);
        assertTrue("event queue empty before",
                queue.isEmpty());

        new ReadyForWorldState(buildBody()).execute(pl);

        assertFalse("must enqueue the deferred populate event",
                queue.isEmpty());
        GameServerEvent first = (GameServerEvent) queue.getFirst();
        assertEquals("WorldStatePopulateEvent",
                first.getClass().getSimpleName());
    }

    @Test
    public void nullPlayerEarlyReturns() {
        new ReadyForWorldState(buildBody()).execute((Player) null);
        // Pass = no NPE.
    }

    @Test
    public void noCharacterEarlyReturns() throws Exception {
        // Player without a character → handler short-circuits.
        Player pl = PacketTestFixture.newPlayer();
        Field f = Player.class.getDeclaredField("pc");
        f.setAccessible(true);
        f.set(pl, null);

        Field eventListField = Player.class.getDeclaredField("eventList");
        eventListField.setAccessible(true);
        PriorityList queue = (PriorityList) eventListField.get(pl);

        new ReadyForWorldState(buildBody()).execute(pl);

        assertTrue("no character → no event scheduled",
                queue.isEmpty());
    }

    @Test
    public void deferredEventExecutesWithoutThrowingOnNullZone()
            throws Exception {
        // Drive the deferred event by hand — fixture player has
        // no Zone, so the zone-population branches must
        // gracefully skip (no NPE) and the rest of the burst
        // must still fire.
        Player pl = PacketTestFixture.newPlayer();
        new ReadyForWorldState(buildBody()).execute(pl);

        Field f = Player.class.getDeclaredField("eventList");
        f.setAccessible(true);
        PriorityList queue = (PriorityList) f.get(pl);
        GameServerEvent populate =
                (GameServerEvent) queue.getFirst();

        // Pass = no exception escaped through the burst.
        populate.execute(pl);
    }

    @Test
    public void multipleClientReadiesScheduleMultipleEvents()
            throws Exception {
        // Each C→S 0x03/0x24 enqueues one populate event. The
        // client may send several on retry, so the handler must
        // tolerate the spam (no de-duplication required —
        // retail's burst is idempotent at the wire level).
        Player pl = PacketTestFixture.newPlayer();
        Field f = Player.class.getDeclaredField("eventList");
        f.setAccessible(true);
        PriorityList queue = (PriorityList) f.get(pl);

        new ReadyForWorldState(buildBody()).execute(pl);
        new ReadyForWorldState(buildBody()).execute(pl);
        new ReadyForWorldState(buildBody()).execute(pl);

        // Drain three populate events from the queue.
        int populates = 0;
        while (!queue.isEmpty()) {
            GameServerEvent e = (GameServerEvent) queue.removeFirst();
            if ("WorldStatePopulateEvent".equals(
                    e.getClass().getSimpleName())) {
                populates++;
            }
        }
        assertEquals("3 ReadyForWorldState calls = 3 events",
                3, populates);
    }
}
