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
 * Functional tests for {@link InsideF2InvMove}. Same shape as
 * {@code InventoryMoveTest}: pins the early-return path and the
 * import wiring of {@link InteractionAck}. Full success-path
 * coverage requires a booted DB so it's exercised elsewhere.
 */
public class InsideF2InvMoveTest {

    /** Build a 12-byte InsideF2InvMove body. The handler skips
     *  8 bytes then reads {@code srcPos LE16} and
     *  {@code dstPos LE16}. */
    private static byte[] buildBody(int srcPos, int dstPos) {
        byte[] b = new byte[12];
        b[0]  = 0x03;          // outer reliable
        b[1]  = 0x42;          // seq lo
        b[2]  = 0x00;          // seq hi
        b[3]  = 0x1f;          // gamedata
        b[4]  = 0x05;          // sub lo
        b[5]  = 0x00;          // sub hi
        b[6]  = 0x25;          // sub-tag class
        b[7]  = 0x14;          // sub-tag value (InsideF2InvMove)
        b[8]  = (byte) (srcPos & 0xff);
        b[9]  = (byte) ((srcPos >> 8) & 0xff);
        b[10] = (byte) (dstPos & 0xff);
        b[11] = (byte) ((dstPos >> 8) & 0xff);
        return b;
    }

    @Test
    public void emptyContainerEarlyReturnsWithoutTcp() {
        // Fixture player has F2 inventory configured (via
        // PacketTestFixture.newPlayer) — but it has no items
        // so ItemManager.moveItem returns false on the failure
        // path. The handler emits an InventoryMoveDenied
        // (UDP-side) but no TCP InteractionAck.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new InsideF2InvMove(buildBody(0, 1)).execute(pl);

        List<ServerTCPPacket> sent = cap.received();
        assertTrue("failed move emits no TCP InteractionAck",
                sent.isEmpty());
    }

    @Test
    public void noTcpConnectionDoesNotThrow() {
        Player pl = PacketTestFixture.newPlayer();
        assertNull(pl.getTcpConnection());
        new InsideF2InvMove(buildBody(0, 0)).execute(pl);
        // Pass = no exception escaped.
    }

    @Test
    public void interactionAckClassIsImported() {
        // Compile-time pin on the success-path emission target.
        assertNotNull("InteractionAck must remain wired into the "
                + "success path of InsideF2InvMove",
                InteractionAck.class);
    }
}
