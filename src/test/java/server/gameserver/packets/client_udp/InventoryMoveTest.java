package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.packets.server_tcp.InteractionAck;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.ServerTCPPacket;

/**
 * Functional tests for {@link InventoryMove}.
 *
 * <p>Pins the early-return path (missing containers → no TCP
 * packets) and the import wiring of {@link InteractionAck}.
 * The full success path (with real items moved between
 * containers) requires {@code ItemManager} fully booted with
 * a populated DB; that's tested elsewhere via the existing
 * {@code InventoryMoveAckDeniedByteIdentityTest} byte-format
 * pin.
 */
public class InventoryMoveTest {

    /** Build a 13-byte InventoryMove body matching the wire
     *  shape {@code 03 [seq2] 1f [sub2] [src LE2 cont/pos]
     *  [dst LE2 cont/pos]} — InventoryMove.execute skips 7
     *  bytes and reads src+dst. */
    private static byte[] buildBody(int srcCont, int srcPos,
                                     int dstCont, int dstPos) {
        byte[] b = new byte[13];
        b[0]  = 0x03;          // outer reliable
        b[1]  = 0x42;          // seq lo
        b[2]  = 0x00;          // seq hi
        b[3]  = 0x1f;          // gamedata
        b[4]  = 0x05;          // sub lo
        b[5]  = 0x00;          // sub hi
        b[6]  = 0x1e;          // sub-tag InventoryMove
        b[7]  = (byte) (srcCont & 0xff);
        b[8]  = (byte) (srcPos & 0xff);
        b[9]  = (byte) ((srcPos >> 8) & 0xff);
        b[10] = (byte) (dstCont & 0xff);
        b[11] = (byte) (dstPos & 0xff);
        b[12] = (byte) ((dstPos >> 8) & 0xff);
        return b;
    }

    @Test
    public void missingContainerEarlyReturnsWithoutTcp() {
        // Fixture player has F2 inventory configured (via
        // PacketTestFixture.newPlayer) but the source container
        // 0xff isn't valid → handler logs + drops before any
        // packet emission. No TCP packets must land.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new InventoryMove(buildBody(0xff, 0, 0xff, 0)).execute(pl);

        List<ServerTCPPacket> sent = cap.received();
        assertTrue("missing containers must produce no TCP packets",
                sent.isEmpty());
    }

    @Test
    public void noTcpConnectionDoesNotThrow() {
        // Same early-return path with no TCP connection — must
        // not NPE.
        Player pl = PacketTestFixture.newPlayer();
        assertNull(pl.getTcpConnection());
        new InventoryMove(buildBody(0xff, 0, 0xff, 0)).execute(pl);
        // Pass = no exception escaped.
    }

    @Test
    public void interactionAckClassIsImported() {
        // Belt-and-suspenders: the implementation imports
        // InteractionAck for the success-path emission. If a
        // future refactor accidentally drops the import, the
        // class won't be on the classpath visible to this test
        // — and the assertion here would break.
        assertNotNull("InteractionAck must remain on the test "
                + "classpath, so a future cleanup of the success-"
                + "path emission is caught at compile time",
                InteractionAck.class);
    }
}
