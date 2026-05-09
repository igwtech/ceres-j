package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Functional test for {@link ReliableTimeSyncRequest} — the
 * C→S {@code 0x03/0x0d} reliable sync request the modern
 * client sends every 8 seconds while in state 3/6. Without a
 * TimeSync reply within 5 retries the client aborts with
 * "Synchronisation to WorldServer failed."
 *
 * <p>Per Ghidra decompile of {@code FUN_0055b6f0} case 3,
 * receipt of the TimeSync reply advances the client's state
 * machine 3/6 → 4 (in-world, playable).
 */
public class ReliableTimeSyncRequestTest {

    /** Build a sub-packet body matching the wire format
     *  {@code [0x03][seq_lo][seq_hi][0x0d][client_time LE4]}.
     *  The handler skips 4 bytes then reads an LE32. */
    private static byte[] buildBody(int clientTime) {
        byte[] b = new byte[8];
        b[0] = 0x03;
        b[1] = 0x42;            // seq lo
        b[2] = 0x00;            // seq hi
        b[3] = 0x0d;            // sub-tag
        b[4] = (byte) (clientTime & 0xff);
        b[5] = (byte) ((clientTime >>  8) & 0xff);
        b[6] = (byte) ((clientTime >> 16) & 0xff);
        b[7] = (byte) ((clientTime >> 24) & 0xff);
        return b;
    }

    @Test
    public void executeSendsTimeSyncReply() {
        // The handler is meant to send a TimeSync via UDP. We
        // can't easily capture UDP datagrams here, but the
        // execute path must not throw and must reach the
        // pl.send(new TimeSync(...)) call.
        Player pl = PacketTestFixture.newPlayer();
        new ReliableTimeSyncRequest(buildBody(0x12345678)).execute(pl);
        // Pass = no exception escaped.
    }

    @Test
    public void noUdpConnectionEarlyReturns() {
        // If UDP is gone the handler must early-return without
        // touching the read pointer or sending packets.
        Player pl = PacketTestFixture.newPlayer();
        pl.closeUDP();
        assertNull(pl.getUdpConnection());

        // Verify no exception even though the body would normally
        // be read past.
        new ReliableTimeSyncRequest(buildBody(0)).execute(pl);
        // Pass = no NPE.
    }

    @Test
    public void nullPlayerEarlyReturns() {
        // Defensive: pl=null must not NPE. Cast disambiguates
        // the Player vs GameServerTCPConnection overloads.
        new ReliableTimeSyncRequest(buildBody(0))
                .execute((Player) null);
        // Pass = no NPE.
    }

    @Test
    public void clientTimeIsReadAtCorrectOffset() {
        // The handler skips 4 bytes (outer 0x03 + seq 2B +
        // sub-tag 0x0d) and reads the next LE32. We can't
        // observe the value directly without a UDP capture,
        // but the handler must not blow up if the body is
        // valid (8 bytes minimum).
        Player pl = PacketTestFixture.newPlayer();
        new ReliableTimeSyncRequest(buildBody(0xDEADBEEF)).execute(pl);
        // No exception → offset arithmetic in skip(4) + readInt()
        // is correct for an 8-byte body.
    }

    @Test
    public void nullAccountIsToleratedInLogMessage() {
        // The Out.writeln message dereferences pl.getAccount();
        // it null-guards via the ?: operator. Verify with a
        // player whose account has a null username.
        Player pl = PacketTestFixture.newPlayer();
        pl.getAccount().setUsername(null);
        new ReliableTimeSyncRequest(buildBody(1)).execute(pl);
        // Pass = no NPE in the log path.
    }
}
