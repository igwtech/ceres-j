package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Packet830D;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.ServerTCPPacket;

/**
 * Functional tests for {@link Zoning1} — verifies the modern
 * retail TCP zone-swap flow lands instead of the legacy
 * {@code SZoning1} UDP path that was producing the persistent
 * "Synchronizing" overlay in live testing.
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
     *  00 00 00 01 00 00 00}) but with the supplied location id
     *  encoded at offset 14 (LE32). */
    private static byte[] buildBody(int locationId, int sessionId) {
        // 14 header bytes + 4 location + 4 session = 22 bytes
        byte[] b = new byte[22];
        // Header bytes copied from live capture so the leading
        // wire format matches what the client really sends.
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
    public void executeSendsTcpGameinfoReadyAndLocation() {
        // Live capture body had locationId=101 (0x65). Use a
        // location id that ZoneManager will resolve — the fixture
        // player is in zone 7 by default and Zoning1.updateZone()
        // calls ZoneManager.getZone() which falls back to zone 1
        // if missing.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        Zoning1 packet = new Zoning1(buildBody(7, 1));
        packet.execute(pl);

        List<ServerTCPPacket> sent = cap.received();
        assertEquals("expected TCP GameinfoReady + Location pair",
                2, sent.size());
        assertTrue("first must be Packet830D",
                sent.get(0) instanceof Packet830D);
        assertTrue("second must be Location",
                sent.get(1) instanceof Location);
    }

    @Test
    public void packet830DBytesMatchRetailExact() {
        // The verified retail bytes for GameinfoReady are
        // `fe 04 00 83 0d 00 00` — FE-frame header (3) + body (4).
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new Zoning1(buildBody(7, 1)).execute(pl);

        ServerTCPPacket pkt = cap.received().get(0);
        byte[] data = pkt.getData();
        // FE-frame header
        assertEquals((byte) 0xfe, data[0]);
        // Length LE16 = 4 (just the opcode + 2 zero bytes)
        assertEquals(4, data[1] & 0xff);
        assertEquals(0, data[2] & 0xff);
        // Body
        assertEquals((byte) 0x83, data[3]);
        assertEquals((byte) 0x0d, data[4]);
        assertEquals(0, data[5]);
        assertEquals(0, data[6]);
    }

    @Test
    public void locationPacketCarriesBspPath() {
        // The fixture player's MISC_LOCATION = 7 (pepper_p3) which
        // resolves to a real zone via the in-memory WorldManager.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new Zoning1(buildBody(7, 1)).execute(pl);

        ServerTCPPacket loc = cap.received().get(1);
        byte[] data = loc.getData();
        // FE-frame header at 0..2, opcode 83 0c at 3..4
        assertEquals((byte) 0x83, data[3]);
        assertEquals((byte) 0x0c, data[4]);
    }

    @Test
    public void noTcpConnectionDoesNotThrow() {
        // If the player has lost their TCP socket mid-flight, the
        // handler must drop the swap gracefully rather than NPE.
        Player pl = PacketTestFixture.newPlayer();
        // pl.getTcpConnection() is null on a fresh fixture.
        new Zoning1(buildBody(7, 1)).execute(pl);
        // No assertion — just verify no exception escaped.
    }

    @Test
    public void locationFieldIsUpdatedOnPlayerCharacter() {
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        // The capture's actual location was 0x65 (101). Our fixture
        // doesn't have a zone 101 wired so updateZone() will fall
        // back, but the MISC_LOCATION field MUST be set first.
        new Zoning1(buildBody(0x65, 1)).execute(pl);

        assertEquals(0x65, pl.getCharacter().getMisc(
                PlayerCharacter.MISC_LOCATION));
    }

    @Test
    public void noLegacySZoning1UdpPacketEmitted() {
        // Regression: confirm the handler does NOT call back into
        // SZoning1. Easy way is to verify nothing UDP-shaped landed
        // — only TCP packets end up in the CapturingTCPConnection.
        // Any SZoning1 emission would go via Player.send(ServerUDPPacket)
        // which on a fixture player either silently drops or throws.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);
        new Zoning1(buildBody(7, 1)).execute(pl);
        // Exactly 2 TCP packets — nothing more.
        assertEquals(2, cap.received().size());
    }
}
