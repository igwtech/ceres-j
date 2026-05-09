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
 * Functional test for {@link SyncUDP} — pins the "no-op"
 * contract that fixed a feedback-storm + 10s-disconnect bug.
 *
 * <p>History: an earlier version replied to every client sync
 * with a full zone re-broadcast (UpdateModel + NPCs + players
 * + ZoningEnd). Because each reliable packet produced a sync
 * ACK and each ACK produced a new burst, the loop generated
 * ~4500 syncs in a few seconds. The client stayed stuck on
 * "SYNCHRONIZING INTO CITY ZONE" because the repeated
 * ZoningEnd kept shoving it back into the load state.
 *
 * <p>A subsequent fix made it send just a TimeSync reply, but
 * that burned a reliable sequence number — if the sync arrived
 * from the OLD port during BSP load, the TimeSync went to a
 * closed socket and created a 10s "Msg num X behind"
 * disconnect.
 *
 * <p>Current state: pure no-op. {@code TimeSyncHeartbeat} at
 * ~1.3 Hz handles the periodic-sync need without per-sync
 * reply.
 */
public class SyncUDPTest {

    private static DatagramPacket buildDatagram() {
        byte[] body = new byte[]{0x03, 0x00, 0x01, 0x00,
                0x00, 0x00, 0x00, 0x00};
        try {
            InetAddress addr = InetAddress.getByName("127.0.0.1");
            return new DatagramPacket(body, body.length, addr, 5000);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void executeEmitsNoTcpPackets() {
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new SyncUDP(buildDatagram()).execute(pl);

        assertTrue("must NOT emit TCP packets — that's the prior "
                + "feedback-storm bug",
                cap.received().isEmpty());
    }

    @Test
    public void executeDoesNotThrow() {
        Player pl = PacketTestFixture.newPlayer();
        new SyncUDP(buildDatagram()).execute(pl);
        // Pass = no exception escaped.
    }

    @Test
    public void noConnectionsRequired() {
        // Defensive: handler must work even on a Player with
        // no transports attached.
        Player pl = PacketTestFixture.newPlayer();
        pl.closeUDP();
        assertNull(pl.getUdpConnection());

        new SyncUDP(buildDatagram()).execute(pl);
        // Pass = no NPE.
    }

    @Test
    public void multipleInvocationsAreSafe() {
        // Stateless. The earlier "feedback storm" version would
        // have produced a multiplied burst here; this no-op
        // version produces nothing.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        for (int i = 0; i < 10; i++) {
            new SyncUDP(buildDatagram()).execute(pl);
        }
        assertTrue("10 syncs must produce zero packets",
                cap.received().isEmpty());
    }
}
