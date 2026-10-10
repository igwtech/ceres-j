package server.gameserver;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.packets.server_udp.PacketTestFixture;
import server.testtools.CapturingUDPConnection;

/**
 * One UDPAlive ({@code 0x04}) per session key: retail sends it once and
 * repeats it only as a ~3 s retry while unanswered. A same-key 0x04 after
 * the client's answer restarts the client's reliable seq.
 */
public class AliveSendGateTest {

    private static GameServerUDPConnection con() throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        return new CapturingUDPConnection(
                java.net.InetAddress.getByName("127.0.0.1"), 5000, pl);
    }

    @Test
    public void firstSendOnlyThenRetryAfterTheInterval() throws Exception {
        GameServerUDPConnection c = con();
        assertTrue(c.claimAliveSend(1000));
        assertFalse("handshake burst collapses to one", c.claimAliveSend(1001));
        assertFalse(c.claimAliveSend(1000 + GameServerUDPConnection.ALIVE_RETRY_MIN_MS - 1));
        assertTrue("unanswered retry", c.claimAliveSend(1000 + GameServerUDPConnection.ALIVE_RETRY_MIN_MS));
    }

    @Test
    public void nothingAfterTheClientAnswered() throws Exception {
        GameServerUDPConnection c = con();
        assertTrue(c.claimAliveSend(1000));
        c.markAliveAcked();
        assertFalse(c.claimAliveSend(60_000));
    }

    @Test
    public void reKeyAllowsTheNewSessionsAlive() throws Exception {
        GameServerUDPConnection c = con();
        assertTrue(c.claimAliveSend(1000));
        c.markAliveAcked();
        c.resetSessionForZoneCross();
        assertTrue(c.claimAliveSend(1001));
    }
}
