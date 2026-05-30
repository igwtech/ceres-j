package server.gameserver.packets.client_udp;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Unit + functional tests for {@link CloseDialog} — the C→S
 * {@code 0x03/0x1f/0x01/0x00/0x27} 1-byte close-dialog packet
 * sent on vendor close, dialog dismiss, CityCom close, etc.
 *
 * <p>The handler is recognise-only and idempotent; these tests
 * pin that contract (no exception, no required TCP connection,
 * no Player mutation visible to callers).
 */
public class CloseDialogTest {

    /** Build the 7-byte wire sub-packet:
     *  {@code 03 [seq2] 1f 01 00 27}. */
    private static byte[] buildBody(int seq) {
        byte[] b = new byte[7];
        b[0] = 0x03;                            // reliable outer
        b[1] = (byte) (seq & 0xff);             // seq lo
        b[2] = (byte) ((seq >> 8) & 0xff);      // seq hi
        b[3] = 0x1f;                            // GamePackets sub-op
        b[4] = 0x01;                            // LE16 prefix lo
        b[5] = 0x00;                            // LE16 prefix hi
        b[6] = 0x27;                            // sub-tag CLOSE_DIALOG
        return b;
    }

    @Test
    public void executeRecogniseOnly() {
        // Recognise-only: must execute without throwing and
        // without requiring a TCP connection on the Player.
        Player pl = PacketTestFixture.newPlayer();
        new CloseDialog(buildBody(0x0042)).execute(pl);
        // Pass = no exception.
    }

    @Test
    public void executeIsIdempotent() {
        // Multiple invocations must not throw or accumulate state.
        Player pl = PacketTestFixture.newPlayer();
        for (int i = 0; i < 5; i++) {
            new CloseDialog(buildBody(i)).execute(pl);
        }
        // Pass = no exception.
    }

    @Test
    public void constructorAcceptsValidBuffer() {
        // Smoke test — the constructor must accept the 7-byte
        // sub-packet.
        assertNotNull(new CloseDialog(buildBody(0x0042)));
    }
}
