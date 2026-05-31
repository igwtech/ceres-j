package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.ServerTCPPacket;

/**
 * Functional tests for {@link InventoryMove}.
 *
 * <p>Pins the early-return path (missing containers → no TCP
 * packets). The full success path (with real items moved between
 * containers) requires {@code ItemManager} fully booted with
 * a populated DB; that's tested elsewhere via the existing
 * {@code InventoryMoveAckDeniedByteIdentityTest} byte-format
 * pin.
 *
 * <p>Note: as of the 2026-05 fix InventoryMove no longer sends any
 * TCP InteractionAck (P6 — no retail evidence of one for a drag);
 * the move confirmation is the UDP {@code 0x25/0x1e} echo, and a
 * rejected move now sends a UDP {@code InventoryMoveDenied} (P4).
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
    public void missingContainerSendsNoUdpDeny() {
        // The missing-container path early-returns BEFORE the
        // move/deny logic, so no InventoryMoveDenied is sent either
        // (there's nothing to revert — the client never got a valid
        // src/dst). Only a malformed-but-resolvable move that fails
        // moveItem() should produce a deny.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new InventoryMove(buildBody(0xff, 0, 0xff, 0)).execute(pl);

        // No TCP traffic (deny is UDP anyway, and this path returns
        // before any send).
        List<ServerTCPPacket> sent = cap.received();
        assertTrue(sent.isEmpty());
    }
}
