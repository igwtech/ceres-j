package server.gameserver.packets.server_udp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for the DELTA form of {@link UpdateModel} — the
 * weapon-draw {@code 0x03/0x2f} UpdateModel that tells the client which
 * weapon MODEL to render in-hand after an equip.
 *
 * <p>Retail draw pcap (2026-06-01, ts ~497.7): in the same reliable
 * burst right after the {@code EquipStateAck} slot-state ack, retail's
 * DRAW (slot 0x01) emits a reliable {@code 0x03/0x2f} delta with a
 * HAND-ONLY inner body:
 * <pre>
 *   2f 01 00 02 02 0000 02 0a [handModelId LE2]
 * </pre>
 * where {@code 01 00} is the constant self localId (NOT the map id) and
 * {@code 0xffff} in the hand slot means empty / unarmed. Retail's draw
 * delta carries NO trailing {@code 02 0b} on-back record (the earlier
 * Ceres delta wrongly appended {@code 02 0b ffff}).
 *
 * <p>This pins the exact 11-byte inner body for a known in-hand value
 * (Stiletto {@code type_id 19 → defs.weapons.id 19 = 0x13} — the weapon
 * DEF id, NOT {@code items.modelid 25}; see {@code ItemInfoManager
 * .getWeaponDefId}) so the wire bytes can't regress. The legacy
 * three-arg hand+back form is still covered separately.
 */
public class UpdateModelDeltaByteIdentityTest {

    private static byte[] datagramBytes(UpdateModel pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP1303):
     *  {@code [0x13][counter LE2][counter+sk LE2][size LE2][0x03][seq LE2][0x2f][body...]}.
     *  The inner body (from the {@code 0x2f} sub-opcode onward) starts at
     *  offset 10. */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",    0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03", 0x03, datagram[7] & 0xFF);
        assertEquals("sub-op 0x2f",   0x2f, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 10, body, 0, len);
        return body;
    }

    @Test
    public void stilettoDrawDeltaBytesByteEqual() {
        // mapId deliberately non-1 to prove the localId is the constant
        // 01 00 and not the map id.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0x1234);

        // Stiletto: defs.items id 19 → defs.weapons.id 19 (0x13). The
        // in-hand TLV carries the weapon DEF id, not items.modelid (25).
        // Hand-only delta (retail draw1 shape) — NO trailing 02 0b record.
        byte[] body = extractInnerBody(datagramBytes(
                new UpdateModel(pl, 19)), 11);
        byte[] expected = {
                0x2f,
                0x01, 0x00,            // self localId LE2 (constant)
                0x02, 0x02, 0x00, 0x00, // stance record 02 02 0000
                0x02, 0x0a, 0x13, 0x00  // in-hand = weapons.id 19 (Stiletto)
        };
        assertArrayEquals("Stiletto draw delta must match pinned bytes",
                expected, body);
    }

    @Test
    public void unarmedDeltaBytesByteEqual() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        // Holster: hand-only delta with hand = none (0xffff).
        byte[] body = extractInnerBody(datagramBytes(
                new UpdateModel(pl, 0xffff)), 11);
        byte[] expected = {
                0x2f,
                0x01, 0x00,
                0x02, 0x02, 0x00, 0x00,
                0x02, 0x0a, (byte) 0xff, (byte) 0xff // hand = none
        };
        assertArrayEquals("unarmed delta must match pinned bytes",
                expected, body);
    }

    @Test
    public void legacyHandAndBackDeltaStillByteEqual() {
        // The three-arg hand+back form is retained for callers that need
        // the on-back record (e.g. future holster-to-back). 15-byte body.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new UpdateModel(pl, 25, 0xffff)), 15);
        byte[] expected = {
                0x2f,
                0x01, 0x00,
                0x02, 0x02, 0x00, 0x00,
                0x02, 0x0a, 0x19, 0x00,
                0x02, 0x0b, (byte) 0xff, (byte) 0xff
        };
        assertArrayEquals("legacy hand+back delta must match pinned bytes",
                expected, body);
    }

    @Test
    public void tagsAreHandAndBack() {
        assertEquals(0x0a, UpdateModel.TAG_HAND_MODEL);
        assertEquals(0x0b, UpdateModel.TAG_BACK_MODEL);
        assertEquals(0xffff, UpdateModel.MODEL_NONE);
    }

    @Test
    public void handOnlyDatagramSizeIsTwentyOneBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 11 (hand-only body incl. 0x2f) = 21 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(21, datagramBytes(new UpdateModel(pl, 25)).length);
    }

    @Test
    public void legacyHandAndBackDatagramSizeIsTwentyFiveBytes() {
        // The three-arg form keeps the 15-byte body → 25-byte datagram.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(25, datagramBytes(new UpdateModel(pl, 25, 0xffff)).length);
    }
}
