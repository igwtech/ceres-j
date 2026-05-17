package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;

/**
 * Byte-identity test for {@link SMovement} — peer-movement broadcast
 * (UDP S→C {@code 0x20} channel).
 *
 * <p>Pins the retail float frame decoded from RETAIL_LONG_PARTY_A
 * S→C peer broadcasts (and cross-checked against the matching C→S
 * frame in RETAIL_NORMAN / RETAIL_DRSTONE):
 *
 * <pre>
 *   20 02 00 27 [Y f32 LE][Z f32 LE][X f32 LE] [status]
 *   e.g. 20 02 00 27 366761c4 9af97fc4 a435e944 40
 *        Y = -901.6127  Z = -1023.9000  X = 1865.6763
 * </pre>
 *
 * <p>Pre-fix bug (task #174): the emitter used type {@code 0x7f} and
 * wrote each coord as {@code uint16 (value + 32000)} — the dead
 * NC1-era frame. The NCE 2.5 client expects float32 LE in both
 * directions (same frame as the StartPos {@code 0x03/0x2c}).
 */
public class SMovementByteIdentityTest {

    /** PacketBuilderUDP13 header = [0x13][ctr LE2][ctr+sk LE2][len LE2] = 7B. */
    private static final int HDR = 7;

    private static byte[] datagramBytes(SMovement pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    @Test
    public void emitsRetailFloatFrame() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, 26982);
        pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, -15661);
        pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, 17998);
        pc.setMisc(PlayerCharacter.MISC_STATUS, 0x40);

        byte[] dg = datagramBytes(new SMovement(pl, pc, 0x0002));

        assertEquals("outer 0x13", 0x13, dg[0] & 0xFF);
        assertEquals("channel 0x20", 0x20, dg[HDR] & 0xFF);
        // entity/map id LE16
        assertEquals("mapId lo", 0x02, dg[HDR + 1] & 0xFF);
        assertEquals("mapId hi", 0x00, dg[HDR + 2] & 0xFF);
        assertEquals("type bitmask 0x27 (Y|Z|X), NOT legacy 0x7f",
                0x27, dg[HDR + 3] & 0xFF);

        ByteBuffer bb = ByteBuffer.wrap(dg).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("Y float LE32", -15661.0f,
                bb.getFloat(HDR + 4), 0.001f);
        assertEquals("Z float LE32", 17998.0f,
                bb.getFloat(HDR + 8), 0.001f);
        assertEquals("X float LE32", 26982.0f,
                bb.getFloat(HDR + 12), 0.001f);
        assertEquals("status byte trailer", 0x40,
                dg[HDR + 16] & 0xFF);
    }

    @Test
    public void doesNotEmitLegacyUint16Frame() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, 100);

        byte[] dg = datagramBytes(new SMovement(pl, pc, 1));

        // Legacy frame would have written type 0x7f and
        // writeShort(100 + 32000) = 0x7D6C at body+4. Assert the
        // type byte is the float-frame 0x27, not 0x7f.
        assertNotEquals("must not regress to legacy uint16 frame",
                0x7f, dg[HDR + 3] & 0xFF);
        assertEquals(0x27, dg[HDR + 3] & 0xFF);
    }
}
