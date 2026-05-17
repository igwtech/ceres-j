package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.PositionUpdate;

/**
 * Task #174 root-cause regression: the NCE 2.5 ("Evolution") client
 * encodes world coordinates as <b>float32 LE</b> on the {@code 0x20}
 * movement channel, in both directions — verified against retail
 * pcaps RETAIL_NORMAN (C→S), RETAIL_DRSTONE (C→S) and
 * RETAIL_LONG_PARTY_A (S→C), and matching the StartPos
 * {@code 0x03/0x2c} float frame ({@link PositionUpdate}).
 *
 * <p>The pre-fix {@link Movement} decoder did {@code readShort() -
 * 32000} per axis: it consumed only 2 bytes of each 4-byte float and
 * subtracted 32000, persisting garbage into MISC_*_COORDINATE. On the
 * next login the (correctly float-encoded) StartPos re-emitted that
 * garbage, so the player spawned at the middle of the map. A
 * zone-cross reconnects as a fresh login, so it hit the same path.
 *
 * <p>Retail evidence used as test vectors below:
 * <pre>
 *   RETAIL_NORMAN  inner sub-packet (0x20 channel):
 *     20 01 00 27 efcf02c4 66e67fc3 ac9f7e45
 *       type 0x27 = Y|Z|X present
 *       Y = float LE32 0xc402cfef = -523.2490
 *       Z = float LE32 0xc37fe666 = -255.9000
 *       X = float LE32 0x457e9fac = 4073.9795
 * </pre>
 */
public class MovementCoordinateFrameTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(
                    s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /**
     * A verbatim retail RETAIL_NORMAN movement sub-packet must decode
     * to the float coordinates it actually carries — NOT to a
     * {@code uint16 - 32000} misread.
     */
    @Test
    public void retailNormanMovementDecodesAsFloat() {
        Player pl = PacketTestFixture.newPlayerWithZone();

        // 20 [entity 0x0001 LE16] 27 [Y f32][Z f32][X f32]
        byte[] sub = hex("20 0100 27 efcf02c4 66e67fc3 ac9f7e45");
        new Movement(sub).execute(pl);

        PlayerCharacter pc = pl.getCharacter();
        // Math.round of the retail floats.
        assertEquals("Y must decode from float LE32 (-523.249 → -523)",
                -523, pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
        assertEquals("Z must decode from float LE32 (-255.900 → -256)",
                -256, pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
        assertEquals("X must decode from float LE32 (4073.979 → 4074)",
                4074, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));

        // The pre-fix uint16-32000 misread would have produced these
        // garbage values — assert we are NOT doing that any more.
        // readShort() of the first 2 bytes of Y (ef cf) = 0xcfef =
        // 53231; 53231 - 32000 = 21231. That nonsense is exactly the
        // "spawn at map centre" corruption.
        assertNotEquals("must not be the legacy uint16-32000 misread",
                21231, pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
    }

    /**
     * Partial-axis updates: type bit 0x04 alone (X only) must consume
     * exactly one float and leave Y/Z untouched. Guards the per-axis
     * float advance.
     */
    @Test
    public void partialAxisUpdateConsumesOneFloatEach() {
        Player pl = PacketTestFixture.newPlayerWithZone();
        PlayerCharacter pc = pl.getCharacter();
        pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, 111);
        pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, 222);

        // type 0x04 = X only, X = float 1234.0 (0x449a4000 LE =
        // 0040 9a44).
        byte[] sub = hex("20 0100 04 00409a44");
        new Movement(sub).execute(pl);

        assertEquals("X updated from its float", 1234,
                pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
        assertEquals("Y untouched (bit not set)", 111,
                pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
        assertEquals("Z untouched (bit not set)", 222,
                pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
    }

    /**
     * End-to-end #174 proof: persist the exact DB row for char
     * "Asddf" (loc=2, x=26982, y=-15661, z=17998), build the
     * world-entry StartPos, and assert the decoded wire floats equal
     * those persisted coordinates (correct frame, no ±32000 / ±32768
     * skew, no map-centre reset).
     */
    @Test
    public void startPosEmitsPersistedCoordsInCorrectFrame() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setMisc(PlayerCharacter.MISC_LOCATION, 2);
        pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, 26982);
        pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, -15661);
        pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, 17998);

        java.net.DatagramPacket[] dps =
                new PositionUpdate(pl).getDatagramPackets();
        byte[] dg = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, dg, 0, dg.length);

        // [0x13 outer 7B][0x03 reliable 1B][seq LE2] = 10B prefix,
        // then StartPos body. Body Y@7, Z@11, X@15 (float LE32) —
        // pinned by PositionUpdateByteIdentityTest against 4 retail
        // pcaps.
        assertEquals("outer 0x13", 0x13, dg[0] & 0xFF);
        assertEquals("reliable 0x03", 0x03, dg[7] & 0xFF);
        ByteBuffer bb = ByteBuffer.wrap(dg)
                .order(ByteOrder.LITTLE_ENDIAN);
        float y = bb.getFloat(10 + 7);
        float z = bb.getFloat(10 + 11);
        float x = bb.getFloat(10 + 15);

        assertEquals("StartPos Y must equal persisted MISC_Y",
                -15661.0f, y, 0.001f);
        assertEquals("StartPos Z must equal persisted MISC_Z",
                17998.0f, z, 0.001f);
        assertEquals("StartPos X must equal persisted MISC_X",
                26982.0f, x, 0.001f);
    }

    /**
     * Closing the loop: a retail-shaped movement burst followed by a
     * StartPos must round-trip the same coordinate. Feed coords as
     * floats over 0x20, then re-emit StartPos — the value the client
     * sent must be exactly the value it gets back (no frame skew that
     * would teleport the player on relog).
     */
    @Test
    public void movementThenStartPosRoundTrips() {
        Player pl = PacketTestFixture.newPlayerWithZone();
        PlayerCharacter pc = pl.getCharacter();

        // Client reports being at X=26982, Y=-15661, Z=17998
        // (the #174 DB row) — as float32 LE, type 0x07 = Y|Z|X.
        ByteBuffer in = ByteBuffer.allocate(4 + 12)
                .order(ByteOrder.LITTLE_ENDIAN);
        in.put((byte) 0x20).put((byte) 0x01).put((byte) 0x00)
          .put((byte) 0x07);
        in.putFloat(-15661.0f);  // Y
        in.putFloat(17998.0f);   // Z
        in.putFloat(26982.0f);   // X
        new Movement(in.array()).execute(pl);

        assertEquals(-15661,
                pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
        assertEquals(17998,
                pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
        assertEquals(26982,
                pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));

        java.net.DatagramPacket[] dps =
                new PositionUpdate(pl).getDatagramPackets();
        byte[] dg = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, dg, 0, dg.length);
        ByteBuffer bb = ByteBuffer.wrap(dg).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("round-trip Y", -15661.0f, bb.getFloat(10 + 7),
                0.001f);
        assertEquals("round-trip Z", 17998.0f, bb.getFloat(10 + 11),
                0.001f);
        assertEquals("round-trip X", 26982.0f, bb.getFloat(10 + 15),
                0.001f);
    }
}
