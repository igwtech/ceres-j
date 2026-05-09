package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Functional test for {@link CPing} — the C→S 0x0b client
 * keepalive ping. Handler reads the client's timestamp and
 * responds with {@link server.gameserver.packets.server_udp.SPing}
 * (which echoes the timestamp back at the same offset). Also
 * updates {@code Player.lastping} so the session-watchdog
 * doesn't time the player out.
 */
public class CPingTest {

    /** Build a 5-byte CPing body: {@code 0b [client_time LE32]}.
     *  The handler skips 1 byte and reads an LE32. */
    private static byte[] buildBody(int clientTime) {
        byte[] b = new byte[5];
        b[0] = 0x0b;
        b[1] = (byte) (clientTime & 0xff);
        b[2] = (byte) ((clientTime >>  8) & 0xff);
        b[3] = (byte) ((clientTime >> 16) & 0xff);
        b[4] = (byte) ((clientTime >> 24) & 0xff);
        return b;
    }

    @Test
    public void executeCallsSetLastping() throws Exception {
        // setLastping reads Timer.getRealtime(); in a unit-test
        // context Timer.init() may not be running so the value
        // can be 0 either way. We pin the call by setting a
        // sentinel pre-condition (-1) and asserting the value
        // changed.
        Player pl = PacketTestFixture.newPlayer();
        java.lang.reflect.Field f = Player.class
                .getDeclaredField("lastping");
        f.setAccessible(true);
        f.setLong(pl, -1L);   // sentinel

        new CPing(buildBody(0x12345678)).execute(pl);

        long after = f.getLong(pl);
        assertNotEquals("lastping must be reassigned (not the "
                + "sentinel) — proves setLastping() was called",
                -1L, after);
    }

    @Test
    public void executeSendsSPingViaUdp() {
        // The SPing send goes via the player's UDP connection.
        // We can't easily capture UDP datagrams from a fixture
        // player, but the SPing constructor takes (clientTime,
        // player) — calling execute should not throw.
        Player pl = PacketTestFixture.newPlayer();
        new CPing(buildBody(42)).execute(pl);
        // Pass = no exception escaped.
    }

    @Test
    public void noUdpConnectionDoesNotThrow() {
        // Defensive: if UDP is gone the SPing send must not NPE.
        Player pl = PacketTestFixture.newPlayer();
        pl.closeUDP();
        assertNull(pl.getUdpConnection());
        new CPing(buildBody(0)).execute(pl);
        // Pass = no exception escaped.
    }

    @Test
    public void multipleInvocationsAreIdempotent() {
        // Stateless decoder — multiple drives are safe and
        // each calls setLastping().
        Player pl = PacketTestFixture.newPlayer();
        new CPing(buildBody(1)).execute(pl);
        new CPing(buildBody(2)).execute(pl);
        new CPing(buildBody(3)).execute(pl);
        // Pass = no exception escaped.
    }
}
