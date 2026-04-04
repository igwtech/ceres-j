package server.gameserver.packets.server_udp;

import java.net.InetAddress;
import java.net.UnknownHostException;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.GameServerUDPConnection;
import server.gameserver.Player;

/**
 * Lightweight factory for constructing {@link Player} instances suitable for
 * UDP packet-serialisation tests.
 *
 * <p>The production packet-builder classes all depend on a {@code Player} for
 * session counters and transaction IDs. This helper stitches together just
 * enough of the surrounding state (Account, PlayerCharacter, UDP connection)
 * without touching the database, SQLite, ZoneManager or ModelTextureManager.
 *
 * <p>Packets built against a fixture-created player are deterministic
 * <em>except</em> for the random 2-byte UDP session key assigned by
 * {@link GameServerUDPConnection}. Tests that need byte-exact comparisons
 * should seed the key via {@link #newPlayerWithFixedSessionKey(short)}.
 */
public final class PacketTestFixture {

    private PacketTestFixture() {}

    /** Builds a {@link Player} attached to a dummy UDP connection. */
    public static Player newPlayer() {
        Account acc = new Account(42);
        acc.setUsername("tester");

        Player pl = new Player(acc);

        PlayerCharacter pc = new PlayerCharacter();
        pc.setName("Runner");
        pc.setMisc(PlayerCharacter.MISC_ID, 0x12345678);
        pc.setMisc(PlayerCharacter.MISC_CLASS, 4);      // PE
        pc.setMisc(PlayerCharacter.MISC_PROFESSION, 0x0a);
        pc.setMisc(PlayerCharacter.MISC_FACTION, 7);
        pc.setMisc(PlayerCharacter.MISC_LOCATION, 7);   // pepper_p3
        pc.setMisc(PlayerCharacter.MODEL_HEAD, 0x0101);
        pc.setMisc(PlayerCharacter.MODEL_TORSO, 0x0202);
        pc.setMisc(PlayerCharacter.MODEL_LEG, 0x0303);
        pc.setMisc(PlayerCharacter.MODEL_HAIR, 0x0404);
        pc.setMisc(PlayerCharacter.MODEL_BEARD, 0x0505);
        pc.setMisc(PlayerCharacter.TEXTURE_HEAD, 1);
        pc.setMisc(PlayerCharacter.TEXTURE_TORSO, 2);
        pc.setMisc(PlayerCharacter.TEXTURE_LEG, 3);
        pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, 100);
        pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, 200);
        pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, 300);
        pc.setMisc(PlayerCharacter.MISC_ORIENTATION, 128);
        pc.setMisc(PlayerCharacter.MISC_TILT, 0);
        pc.setMisc(PlayerCharacter.MISC_STATUS, 0);
        pc.initContainer(new int[]{1, 2, 3});

        // Directly install character without hitting ZoneManager (which
        // needs the DB to be initialised). setCharacter() would also call
        // registerPlayer() which we don't need for the builder tests.
        try {
            java.lang.reflect.Field pcField = Player.class.getDeclaredField("pc");
            pcField.setAccessible(true);
            pcField.set(pl, pc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Unable to install PlayerCharacter", e);
        }

        pl.setMapID(1);

        try {
            InetAddress addr = InetAddress.getByName("127.0.0.1");
            GameServerUDPConnection con = new GameServerUDPConnection(addr, 5000, pl);
            pl.setUdpConnection(con);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
        return pl;
    }

    /**
     * Same as {@link #newPlayer()} but overrides the random UDP session key
     * with a fixed value so packet outputs are byte-exact reproducible.
     */
    public static Player newPlayerWithFixedSessionKey(short sessionKey) {
        Player pl = newPlayer();
        try {
            java.lang.reflect.Field f = GameServerUDPConnection.class
                    .getDeclaredField("udp13Sessionkey");
            f.setAccessible(true);
            f.set(pl.getUdpConnection(), sessionKey);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return pl;
    }

    /** Hex-encode a byte array for diagnostic assertion messages. */
    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            sb.append(String.format("%02x ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    /** Convenience: combine all DatagramPackets' byte arrays into one. */
    public static byte[] flatten(java.net.DatagramPacket[] packets) {
        int total = 0;
        for (java.net.DatagramPacket dp : packets) total += dp.getLength();
        byte[] out = new byte[total];
        int pos = 0;
        for (java.net.DatagramPacket dp : packets) {
            System.arraycopy(dp.getData(), dp.getOffset(), out, pos, dp.getLength());
            pos += dp.getLength();
        }
        return out;
    }
}
