package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.Test;

import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Functional tests for {@link AbortSession} — the C→S 0x08
 * client-initiated session abort. Handler closes both the TCP
 * and UDP connections.
 *
 * <p>Per CLAUDE.md / PROTOCOL notes, the modern client sends
 * 0x08 ~15 seconds after its last unACKed reliable, so a clean
 * close on this path keeps the server-side player thread from
 * lingering with dead sockets.
 */
public class AbortSessionTest {

    /** Build a tiny DatagramPacket with the 0x08 sub-packet
     *  body. The handler doesn't parse the body but the
     *  parent class needs the bytes to construct. */
    private static DatagramPacket buildDatagram() {
        byte[] body = new byte[]{0x08};
        try {
            InetAddress addr = InetAddress.getByName("127.0.0.1");
            return new DatagramPacket(body, body.length, addr, 5000);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void executeClosesTcpConnection() {
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);
        assertNotNull("precondition: TCP attached",
                pl.getTcpConnection());

        new AbortSession(buildDatagram()).execute(pl);

        assertNull("TCP connection must be closed (= null) after "
                + "AbortSession", pl.getTcpConnection());
    }

    @Test
    public void executeClosesUdpConnection() {
        Player pl = PacketTestFixture.newPlayer();
        // PacketTestFixture installs a UDP connection on
        // construction.
        assertNotNull("precondition: UDP attached",
                pl.getUdpConnection());

        new AbortSession(buildDatagram()).execute(pl);

        assertNull("UDP connection must be closed (= null) after "
                + "AbortSession", pl.getUdpConnection());
    }

    @Test
    public void executeIsIdempotentOnAlreadyClosedConnections() {
        // After one AbortSession both connections are null.
        // A second AbortSession must be safe (null-guarded
        // closes; no NPE).
        Player pl = PacketTestFixture.newPlayer();
        pl.setTcpConnection(new CapturingTCPConnection());

        new AbortSession(buildDatagram()).execute(pl);
        // Both null now.
        new AbortSession(buildDatagram()).execute(pl);
        // Still null, no exception.
        assertNull(pl.getTcpConnection());
        assertNull(pl.getUdpConnection());
    }

    @Test
    public void noTcpConnectionDoesNotThrow() {
        // Player has UDP but no TCP — abort must close UDP and
        // leave TCP null without NPE.
        Player pl = PacketTestFixture.newPlayer();
        assertNull(pl.getTcpConnection());

        new AbortSession(buildDatagram()).execute(pl);

        assertNull(pl.getUdpConnection());
        assertNull(pl.getTcpConnection());
    }
}
