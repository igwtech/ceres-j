package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Functional tests for {@link Zoning1}.
 *
 * <p>Retail behaviour decoded 2026-05-14 from
 * {@code RETAIL_PLAZA_CROSSZONE}: Zoning1 is a pure notification.
 * The server sends <strong>nothing</strong> in reply — it just
 * records the destination zone. The client preloads the BSP on
 * its own and ~500 ms later sends Zoning2, which is what triggers
 * the TCP Location + UDPAlive + session-reset response (see
 * {@link Zoning2}).
 *
 * <p>An earlier implementation answered Zoning1 immediately with
 * {@code Packet830D + Location}; that premature reply derailed the
 * client's state machine and caused the plaza_p1 → plaza_p3 hang.
 */
public class Zoning1Test {

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s+", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    /** Build a 22-byte Zoning1 sub-packet body (matches the live
     *  capture {@code 02 e7 02 22 0d 00 01 01 00 00 00 00 04 00 65
     *  00 00 00 01 00 00 00}) with the supplied location id encoded
     *  at offset 14 (LE32). */
    private static byte[] buildBody(int locationId, int sessionId) {
        byte[] b = new byte[22];
        byte[] header = hex("02 e7 02 22 0d 00 01 01 00 00 00 00 04 00");
        System.arraycopy(header, 0, b, 0, 14);
        b[14] = (byte) (locationId        & 0xff);
        b[15] = (byte) ((locationId >> 8 ) & 0xff);
        b[16] = (byte) ((locationId >> 16) & 0xff);
        b[17] = (byte) ((locationId >> 24) & 0xff);
        b[18] = (byte) (sessionId        & 0xff);
        b[19] = (byte) ((sessionId >> 8 ) & 0xff);
        b[20] = (byte) ((sessionId >> 16) & 0xff);
        b[21] = (byte) ((sessionId >> 24) & 0xff);
        return b;
    }

    @Test
    public void zoning1SendsNothing() {
        // Retail sends NO reply to Zoning1. The TCP zone-swap
        // packets are emitted in response to Zoning2.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new Zoning1(buildBody(7, 1)).execute(pl);

        assertEquals("Zoning1 must not emit any TCP packet",
                0, cap.received().size());
    }

    @Test
    public void targetZoneIsRecordedAsPendingNotCommitted() {
        // Zoning1 must NOT commit the zone switch — it only records
        // the destination as pending. MISC_LOCATION stays at the
        // source zone until Zoning2 commits. Committing on Zoning1
        // streams the destination zone's state to a client still in
        // the source BSP and wedges the cross.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        int sourceZone = pl.getCharacter().getMisc(
                PlayerCharacter.MISC_LOCATION);

        new Zoning1(buildBody(0x65, 1)).execute(pl);

        assertEquals("pending zone must be the Zoning1 target",
                0x65, pl.getPendingZoneId());
        assertEquals("MISC_LOCATION must NOT change on Zoning1",
                sourceZone, pl.getCharacter().getMisc(
                        PlayerCharacter.MISC_LOCATION));
    }

    @Test
    public void noTcpConnectionDoesNotThrow() {
        // Zoning1 no longer touches the TCP connection at all, but
        // keep the guard test: a fixture with no socket must not
        // NPE.
        Player pl = PacketTestFixture.newPlayer();
        new Zoning1(buildBody(7, 1)).execute(pl);
        // No assertion — just verify no exception escaped.
    }

    @Test
    public void coordsArePreservedOnZoneCross() {
        // Regression pin (2026-05-14): the handler must NOT zero
        // X/Y/Z on Zoning1. Zeroing broke the reconnect spawn
        // (re-login ran WorldEntry with (0,0,0), not a valid spawn
        // point in most BSPs → client stuck on loading splash).
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        pl.getCharacter().setMisc(PlayerCharacter.MISC_X_COORDINATE, 12345);
        pl.getCharacter().setMisc(PlayerCharacter.MISC_Y_COORDINATE, 6789);
        pl.getCharacter().setMisc(PlayerCharacter.MISC_Z_COORDINATE, 4321);

        new Zoning1(buildBody(7, 1)).execute(pl);

        assertEquals("X preserved", 12345,
                pl.getCharacter().getMisc(PlayerCharacter.MISC_X_COORDINATE));
        assertEquals("Y preserved", 6789,
                pl.getCharacter().getMisc(PlayerCharacter.MISC_Y_COORDINATE));
        assertEquals("Z preserved", 4321,
                pl.getCharacter().getMisc(PlayerCharacter.MISC_Z_COORDINATE));
    }
}
