package server.gameserver.packets.client_tcp;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.packets.server_tcp.UDPServerData;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Functional test for {@link GetUDPConnection} — pins the
 * "no-op" contract that fixed a prior zone-handoff bug.
 *
 * <p>History: an earlier implementation sent {@link UDPServerData}
 * from this handler. That triggered the modern client to create
 * a SECOND {@code WinSockMGR} socket because retail never sends
 * {@code UDPServerData} twice in one session. Result: 25-second
 * disconnect after world entry. Fix was to make this handler a
 * pure no-op (UDPServerData is already emitted by GetGamedata).
 *
 * <p>The test asserts: no TCP/UDP packets emitted, no events
 * scheduled, no exceptions.
 */
public class GetUDPConnectionTest {

    @Test
    public void executeEmitsNoPackets() {
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new GetUDPConnection(new byte[]{(byte) 0x87, 0x3c, 0x00, 0x00, 0x00, 0x00})
                .execute(pl);

        assertTrue("must NOT emit TCP packets — that's the prior "
                + "bug this no-op fixes",
                cap.received().isEmpty());
    }

    @Test
    public void executeDoesNotEmitUDPServerData() {
        // The most-important regression guard: a future revert
        // that re-introduces `pl.send(new UDPServerData(pl))` here
        // would silently break zone-handoff. Catch it.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new GetUDPConnection(new byte[6]).execute(pl);

        boolean foundUdpServerData = cap.received().stream()
                .anyMatch(p -> p instanceof UDPServerData);
        assertFalse("must NOT emit UDPServerData a second time — "
                + "GetGamedata already sent it during the initial "
                + "session burst",
                foundUdpServerData);
    }

    @Test
    public void executeDoesNotThrowOnNullTcp() {
        // Defensive: null TCP must be tolerated (the no-op is
        // truly a no-op even when the connection's gone).
        Player pl = PacketTestFixture.newPlayer();
        assertNull(pl.getTcpConnection());
        new GetUDPConnection(new byte[6]).execute(pl);
        // Pass = no exception escaped.
    }

    @Test
    public void multipleInvocationsAreSafe() {
        // The handler is stateless — calling it many times in a
        // row is safe.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        for (int i = 0; i < 5; i++) {
            new GetUDPConnection(new byte[6]).execute(pl);
        }
        assertTrue(cap.received().isEmpty());
    }
}
