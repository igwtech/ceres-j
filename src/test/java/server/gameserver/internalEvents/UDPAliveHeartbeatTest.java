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
 * S→C UDPAlive retry while the client has not answered.
 *
 * <p>Retail re-sends the UDPAlive only until the client answers it
 * with a transport {@code 0x03}; after that it sends none. The
 * modern client resets its reliable state on every UDPAlive, so a
 * retry after the answer causes a full-backlog retransmit storm.
 * Unanswered retries are bounded to {@link
 * UDPAliveHeartbeat#MAX_TICKS}.
 */
public class UDPAliveHeartbeatTest {

    @Test
    public void noUdpAliveOnceClientAnswered() throws Exception {
        // Regression (2026-10-04): the unconditional 4-tick
        // heartbeat sent 0x04 after the client had already answered,
        // and each one reset the 64-bit client's reliable state
        // (harness wgate64_v3: 428 NAK/retransmit pairs in 43 s).
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();
        java.net.InetAddress addr =
                java.net.InetAddress.getByName("127.0.0.1");
        server.testtools.CapturingUDPConnection cap =
                new server.testtools.CapturingUDPConnection(
                        addr, 5000, pl);
        pl.setUdpConnection(cap);
        cap.markAliveAcked();

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new UDPAliveHeartbeat().execute(pl);

        assertTrue("no UDPAlive once the client answered",
                cap.received().isEmpty());
        assertTrue("retry chain must end once the client answered",
                queue.isEmpty());
    }

    @Test
    public void retryStopsAsSoonAsClientAnswers() throws Exception {
        // RETAIL_HANNIBAL: unanswered UDPAlive re-sent at ~3 s
        // spacing, then nothing after the client's 0x03.
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();
        java.net.InetAddress addr =
                java.net.InetAddress.getByName("127.0.0.1");
        server.testtools.CapturingUDPConnection cap =
                new server.testtools.CapturingUDPConnection(
                        addr, 5000, pl);
        pl.setUdpConnection(cap);

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new UDPAliveHeartbeat().execute(pl);
        assertEquals("unanswered: one retry sent", 1,
                cap.received().size());
        GameServerEvent next = (GameServerEvent) queue.removeFirst();

        cap.markAliveAcked();
        next.execute(pl);

        assertEquals("no further UDPAlive after the answer", 1,
                cap.received().size());
        assertTrue(queue.isEmpty());
    }

    @Test
    public void zoneCrossRekeyRequiresANewAnswer() throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        pl.getUdpConnection().markAliveAcked();

        pl.getUdpConnection().resetSessionForZoneCross();

        assertFalse("a new session key needs a new 0x03 answer",
                pl.getUdpConnection().isAliveAcked());
    }

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
    public void heartbeatStopsAfterMaxTicks() throws Exception {
        // Regression (2026-05-16): a perpetual UDPAlive heartbeat
        // makes the client reset its reliable-receive window every
        // 3 s → unbounded retransmit storm (45k reqs, FPS collapse,
        // "can't move" post zone-cross). The heartbeat must emit
        // exactly MAX_TICKS UDPAlives then stop rescheduling.
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();
        java.net.InetAddress addr =
                java.net.InetAddress.getByName("127.0.0.1");
        server.testtools.CapturingUDPConnection cap =
                new server.testtools.CapturingUDPConnection(
                        addr, 5000, pl);
        pl.setUdpConnection(cap);

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        // Drive the chain: keep executing the rescheduled instance
        // until it stops enqueuing a successor.
        GameServerEvent ev = new UDPAliveHeartbeat();
        int guard = 0;
        while (ev != null && guard++ < 50) {
            ev.execute(pl);
            ev = queue.isEmpty()
                    ? null
                    : (GameServerEvent) queue.removeFirst();
        }

        assertEquals("must emit exactly MAX_TICKS UDPAlives "
                + "then stop",
                UDPAliveHeartbeat.MAX_TICKS, cap.received().size());
        assertTrue("queue must be empty after the bounded chain ends",
                queue.isEmpty());
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
