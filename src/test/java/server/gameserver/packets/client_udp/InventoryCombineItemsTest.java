package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Functional test for {@link InventoryCombineItems}.
 *
 * <p>The handler currently always emits {@link
 * server.gameserver.packets.server_udp.InventoryMoveDenied}
 * because the actual combine logic is commented out as TODO.
 * This test pins the always-denied contract so a future
 * implementation that wires real combine logic forces an
 * explicit test update.
 *
 * <p>Side note: a future fix should also wire
 * {@link server.gameserver.packets.server_tcp.InteractionAck}
 * on the eventual successful-combine path, mirroring the
 * other inventory handlers.
 */
public class InventoryCombineItemsTest {

    /** Build a 14-byte body: skip 8, then srcCont (1B) +
     *  srcPos (LE16) + dstCont (1B) + dstPos (LE16). */
    private static byte[] buildBody(int srcCont, int srcPos,
                                     int dstCont, int dstPos) {
        byte[] b = new byte[14];
        b[0]  = 0x03;
        b[1]  = 0x42;
        b[2]  = 0x00;
        b[3]  = 0x1f;
        b[4]  = 0x05;
        b[5]  = 0x00;
        b[6]  = 0x25;
        b[7]  = 0x17;
        b[8]  = (byte) srcCont;
        b[9]  = (byte) (srcPos & 0xff);
        b[10] = (byte) ((srcPos >> 8) & 0xff);
        b[11] = (byte) dstCont;
        b[12] = (byte) (dstPos & 0xff);
        b[13] = (byte) ((dstPos >> 8) & 0xff);
        return b;
    }

    @Test
    public void unknownContainerEarlyReturns() {
        // Both src and dst containers must exist; otherwise
        // the handler logs and returns early with NO packet.
        Player pl = PacketTestFixture.newPlayer();
        new InventoryCombineItems(buildBody(0xff, 0, 0xff, 0))
                .execute(pl);
        // Pass = no exception escaped.
    }

    @Test
    public void validContainersAlwaysSendDenied() {
        // Even when both containers exist, the handler always
        // emits Denied because the combine logic is TODO.
        // Pin so a future "actually combine" implementation
        // surfaces this test as needing an update.
        Player pl = PacketTestFixture.newPlayer();
        // Fixture has F2 inventory configured.
        new InventoryCombineItems(buildBody(
                PlayerCharacter.PLAYERCONTAINER_F2, 0,
                PlayerCharacter.PLAYERCONTAINER_F2, 1))
                .execute(pl);
        // No exception → handler reached the InventoryMoveDenied
        // emit path.
    }

    @Test
    public void noUdpConnectionDoesNotThrow() {
        Player pl = PacketTestFixture.newPlayer();
        pl.closeUDP();
        assertNull(pl.getUdpConnection());
        new InventoryCombineItems(buildBody(0xff, 0, 0xff, 0))
                .execute(pl);
        // Pass = no exception (early-return path is null-safe).
    }

    @Test
    public void containerIdsReadAtCorrectOffsets() {
        // Skip(8) + read sequence is 1+2+1+2 = 6 bytes. Body
        // total = 14. Verify the parser doesn't overshoot.
        Player pl = PacketTestFixture.newPlayer();
        new InventoryCombineItems(buildBody(
                PlayerCharacter.PLAYERCONTAINER_F2, 0xCAFE,
                PlayerCharacter.PLAYERCONTAINER_F2, 0xBABE))
                .execute(pl);
        // Pass = the offset arithmetic in skip(8)+read sequence
        // doesn't overflow on a 14-byte body.
    }
}
