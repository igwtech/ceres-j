package server.gameserver.internalEvents;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.packets.server_tcp.TcpKeepalive;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.ServerTCPPacket;
import server.testtools.CapturingUDPConnection;

/**
 * Functional + cadence test for {@link TcpKeepaliveEvent} — the
 * periodic emitter that pushes the {@code 0x83/0x8f} TCP packet
 * for the duration of a session.
 *
 * <h3>Why this exists (task #255)</h3>
 *
 * <p>Retail empirically sends {@code 0x83/0x8f} ~100 times per
 * 17-minute session (one every ~10.9s). Verified 2026-05-24 in
 * pcap RETRY3: 100 emissions over the connected lifetime, mean
 * gap 10.8s, std-dev <100ms. The client treats it as a TCP
 * liveness signal — without it, retail's TCP stack tears down
 * the half-idle connection somewhere around the 30-60s mark and
 * the session times out.
 *
 * <p>This test pins both the cadence ({@link
 * TcpKeepaliveEvent#INTERVAL_MS}) and the self-reschedule
 * contract: on each tick the event must enqueue another copy of
 * itself so the heartbeat keeps running for the whole session.
 */
public class TcpKeepaliveEventTest {

    @Test
    public void intervalMatchesRetailObservedCadence() {
        // Retail mean = 10.9s, observed range 10.6–11.2s. The
        // current INTERVAL_MS is 10s — within tolerance but at the
        // low end. If anyone bumps this back to a 30s+ keepalive
        // (which would re-introduce the retail-style TCP timeout
        // gap) this assertion catches it.
        assertTrue("INTERVAL_MS must stay near retail's ~10.9s",
                TcpKeepaliveEvent.INTERVAL_MS >= 9000
                        && TcpKeepaliveEvent.INTERVAL_MS <= 12000);
    }

    @Test
    public void schedulesItselfAtIntervalInTheFuture() {
        // The no-arg ctor must schedule the FIRST tick at
        // T + INTERVAL_MS from "now". This is what
        // WorldEntryEvent relies on to spread the first
        // keepalive past the initial burst.
        long before = server.tools.Timer.getRealtime();
        TcpKeepaliveEvent ev = new TcpKeepaliveEvent();
        assertTrue("first tick must fire >= INTERVAL_MS in the future",
                ev.eventTime
                        >= before + TcpKeepaliveEvent.INTERVAL_MS);
    }

    @Test
    public void executeEmitsTcpKeepaliveAndReschedules()
            throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        CapturingUDPConnection.replaceOn(pl);
        CapturingTCPConnection tcp = new CapturingTCPConnection();
        pl.setTcpConnection(tcp);
        // The event guards on isloggedin() — flip it on so the
        // execute path runs to completion. (Without this the
        // event silently no-ops and we get a false positive.)
        java.lang.reflect.Field flag =
                Player.class.getDeclaredField("isloggedin");
        flag.setAccessible(true);
        flag.setBoolean(pl, true);

        // Drain anything queued before the test.
        java.lang.reflect.Field qf =
                Player.class.getDeclaredField("eventList");
        qf.setAccessible(true);
        Object queue = qf.get(pl);
        java.lang.reflect.Method isEmpty =
                queue.getClass().getMethod("isEmpty");
        java.lang.reflect.Method removeFirst =
                queue.getClass().getMethod("removeFirst");
        while (!(Boolean) isEmpty.invoke(queue)) {
            removeFirst.invoke(queue);
        }

        new TcpKeepaliveEvent().execute(pl);

        // 1) TcpKeepalive packet was emitted.
        boolean sawKeepalive = false;
        for (ServerTCPPacket p : tcp.received()) {
            if (p instanceof TcpKeepalive) sawKeepalive = true;
        }
        assertTrue("execute must emit a TcpKeepalive packet",
                sawKeepalive);

        // 2) Event re-enqueued itself. Walk the queue and collect
        //    class names — production uses a priority queue so the
        //    relative position isn't fixed.
        Set<String> queued = new HashSet<>();
        while (!(Boolean) isEmpty.invoke(queue)) {
            queued.add(removeFirst.invoke(queue)
                    .getClass().getSimpleName());
        }
        assertTrue("execute must re-enqueue TcpKeepaliveEvent so "
                + "the heartbeat keeps running for the whole "
                + "session, queued = " + queued,
                queued.contains("TcpKeepaliveEvent"));
    }

    @Test
    public void disconnectedPlayerStopsRescheduling() throws Exception {
        // If a Player has dropped (closeUDP/closeTCP), the event
        // must NOT re-enqueue itself — letting the heartbeat die
        // is the only way the event chain terminates.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        CapturingUDPConnection.replaceOn(pl);
        pl.setTcpConnection(new CapturingTCPConnection());
        // loggedin stays false → guard short-circuits.

        // Drain pre-existing queue entries.
        java.lang.reflect.Field qf =
                Player.class.getDeclaredField("eventList");
        qf.setAccessible(true);
        Object queue = qf.get(pl);
        java.lang.reflect.Method isEmpty =
                queue.getClass().getMethod("isEmpty");
        java.lang.reflect.Method removeFirst =
                queue.getClass().getMethod("removeFirst");
        while (!(Boolean) isEmpty.invoke(queue)) {
            removeFirst.invoke(queue);
        }

        new TcpKeepaliveEvent().execute(pl);

        assertTrue("disconnected player must NOT re-enqueue the "
                + "heartbeat (event chain would never terminate)",
                (Boolean) isEmpty.invoke(queue));
    }

    @Test
    public void emitsRetailExactBytes() {
        // Belt-and-suspenders: the bytes TcpKeepaliveEvent puts on
        // the wire MUST match retail's `83 8f 00 00 00 00 00`.
        TcpKeepalive pkt = new TcpKeepalive();
        // FE-frame (3B) + body (7B) = 10B wire
        assertEquals(10, pkt.size());
        byte[] data = pkt.getData();
        byte[] expected = {
                (byte) 0xfe, 0x07, 0x00,           // FE frame, len=7
                (byte) 0x83, (byte) 0x8f,          // opcode
                0x00, 0x00, 0x00, 0x00, 0x00       // 5 trailing zeros
        };
        byte[] actual = new byte[10];
        System.arraycopy(data, 0, actual, 0, 10);
        assertArrayEquals("TcpKeepalive byte-identical to retail",
                expected, actual);
    }
}
