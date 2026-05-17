package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Verifies the task-#182 server-side noclip lever: {@link Player#isNoclip()}
 * genuinely changes whether {@link Movement} commits a client position.
 *
 * <p>Without noclip an out-of-bounds coordinate (|axis| &gt;
 * {@code Movement.SANE_COORD}) is dropped — the
 * {@link PlayerCharacter} keeps its prior coords. With noclip on the
 * same packet is accepted (free flight). In-bounds movement is always
 * accepted regardless of the flag.
 */
public class MovementNoclipAcceptanceTest {

    /**
     * Build a Movement sub-packet body. Layout consumed by
     * {@code Movement.execute()}: {@code skip(3)} then a 1-byte
     * {@code type} mask, then per-set-axis <b>float32 LE</b> in
     * Y,Z,X order (mask bits 0x01,0x02,0x04). The wire frame is
     * IEEE-754 float32 LE per the NCE 2.5 client (task #174,
     * retail-pinned) — NOT the legacy {@code uint16 value+32000}.
     */
    private static byte[] move(int x, int y, int z) {
        int type = 0x01 | 0x02 | 0x04; // Y,Z,X all present
        java.io.ByteArrayOutputStream b =
                new java.io.ByteArrayOutputStream();
        b.write(0); b.write(0); b.write(0);          // skip(3)
        b.write(type);
        writeLEF32(b, y);
        writeLEF32(b, z);
        writeLEF32(b, x);
        return b.toByteArray();
    }

    private static void writeLEF32(java.io.ByteArrayOutputStream b,
                                   float v) {
        int bits = Float.floatToIntBits(v);
        b.write(bits & 0xFF);
        b.write((bits >> 8) & 0xFF);
        b.write((bits >> 16) & 0xFF);
        b.write((bits >> 24) & 0xFF);
    }

    private static Player playerAt(int x, int y, int z) {
        Player pl = PacketTestFixture.newPlayerWithZone();
        PlayerCharacter pc = pl.getCharacter();
        pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, x);
        pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, y);
        pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, z);
        return pl;
    }

    @Test
    public void inBoundsMovementAlwaysAccepted() {
        Player pl = playerAt(0, 0, 0);
        new Movement(move(100, 200, 300)).execute(pl);
        PlayerCharacter pc = pl.getCharacter();
        assertEquals(100, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
        assertEquals(200, pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
        assertEquals(300, pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
    }

    @Test
    public void outOfBoundsRejectedWhenNoclipOff() {
        Player pl = playerAt(11, 22, 33);
        assertFalse(pl.isNoclip());
        // X = 31000 is beyond SANE_COORD (30000); float32 carries it
        // exactly, so the gate (not the wire) is what rejects it.
        new Movement(move(31000, 22, 33)).execute(pl);
        PlayerCharacter pc = pl.getCharacter();
        assertEquals("out-of-bounds X must NOT be committed",
                11, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
        assertEquals(22, pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
        assertEquals(33, pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
    }

    @Test
    public void outOfBoundsAcceptedWhenNoclipOn() {
        Player pl = playerAt(11, 22, 33);
        pl.setNoclip(true);
        new Movement(move(31000, 22, 33)).execute(pl);
        PlayerCharacter pc = pl.getCharacter();
        assertEquals("noclip lets the server accept free-flight X",
                31000, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
    }

    @Test
    public void rejectionIsAtomicAcrossAxes() {
        // Only one axis is out of bounds; the whole position update
        // is vetoed so the player is never left half-moved.
        Player pl = playerAt(1, 2, 3);
        // Z = 33000 > SANE_COORD (float32 carries it exactly).
        new Movement(move(500, 600, 33000)).execute(pl);
        PlayerCharacter pc = pl.getCharacter();
        assertEquals(1, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
        assertEquals(2, pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
        assertEquals(3, pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
    }
}
