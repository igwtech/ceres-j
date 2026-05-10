package server.gameserver.internalEvents;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.UDPAlive;
import server.interfaces.GameServerEvent;
import server.tools.PriorityList;

/**
 * Functional test for {@link UDPAliveHeartbeat} — the ~3 s
 * S→C UDPAlive keepalive (task #158).
 *
 * <p>Retail emits 8 UDPAlives per HANNIBAL session:
 * 4 in the handshake-reply burst + 4 periodic at ~3 s spacing.
 * Without the periodic ones the client's UDP-keepalive
 * expectation drifts. This event mirrors the
 * {@link TimeSyncHeartbeatEvent} self-rescheduling pattern.
 */
public class UDPAliveHeartbeatTest {

    @Test
    public void executeReschedulesItselfWhenLoggedIn()
            throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new UDPAliveHeartbeat().execute(pl);

        assertFalse("heartbeat must self-reschedule when logged in",
                queue.isEmpty());
        GameServerEvent next = (GameServerEvent) queue.getFirst();
        assertEquals("rescheduled event must be UDPAliveHeartbeat",
                "UDPAliveHeartbeat",
                next.getClass().getSimpleName());
    }

    @Test
    public void notLoggedInDoesNotReschedule() throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        // NOT calling setloggedin().

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new UDPAliveHeartbeat().execute(pl);

        assertTrue("heartbeat must die when player not logged in",
                queue.isEmpty());
    }

    @Test
    public void noUdpConnectionDoesNotReschedule() throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();
        pl.closeUDP();

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new UDPAliveHeartbeat().execute(pl);

        assertTrue("heartbeat must die when UDP closed",
                queue.isEmpty());
    }

    @Test
    public void nullPlayerEarlyReturns() {
        new UDPAliveHeartbeat().execute((Player) null);
        // No throw = pass.
    }

    @Test
    public void firstTickFiresAfterIntervalMs() {
        long now = server.tools.Timer.getRealtime();
        UDPAliveHeartbeat hb = new UDPAliveHeartbeat();
        long delay = hb.getEventTime() - now;
        assertTrue("first tick deadline must be >= INTERVAL_MS - 50ms"
                + " (allowing minor scheduler jitter)",
                delay >= UDPAliveHeartbeat.INTERVAL_MS - 50);
        assertTrue("first tick deadline must be <= INTERVAL_MS + 50ms",
                delay <= UDPAliveHeartbeat.INTERVAL_MS + 50);
    }

    @Test
    public void emitsUdpAlivePacketOnTick() throws Exception {
        // Functional verification: each tick sends a UDPAlive
        // packet via pl.send(). Use CapturingUDPConnection to
        // observe the emit.
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();
        java.net.InetAddress addr =
                java.net.InetAddress.getByName("127.0.0.1");
        server.testtools.CapturingUDPConnection cap =
                new server.testtools.CapturingUDPConnection(
                        addr, 5000, pl);
        pl.setUdpConnection(cap);

        new UDPAliveHeartbeat().execute(pl);

        java.util.List<server.interfaces.ServerUDPPacket>
                emitted = cap.received();
        assertEquals("exactly one UDPAlive emitted per tick",
                1, emitted.size());
        assertTrue("emitted packet must be UDPAlive, got "
                + emitted.get(0).getClass().getSimpleName(),
                emitted.get(0) instanceof UDPAlive);
    }
}
