package server.gameserver.packets.client_udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Unit + functional tests for {@link EquipHolster} — pins the
 * 2-byte body shape ({@code 1f &lt;slot&gt;}) and the recognise-only
 * execute contract.
 *
 * <p>Wire byte pin (retail-verified 2026-05-22, 28 observations):
 * {@code 03 [seq2] 1f 01 00 1f [slot]}. The handler is invoked
 * with the full sub-packet (starting at the leading {@code 03});
 * its {@code parseSlot()} skips 7 bytes and reads the slot byte
 * at offset 7.
 */
public class EquipHolsterTest {

    /** Build the 8-byte wire sub-packet for an equip request:
     *  {@code 03 [seq2] 1f 01 00 1f [slot]}. */
    private static byte[] buildBody(int seq, int slot) {
        byte[] b = new byte[8];
        b[0] = 0x03;                            // reliable outer
        b[1] = (byte) (seq & 0xff);             // seq lo
        b[2] = (byte) ((seq >> 8) & 0xff);      // seq hi
        b[3] = 0x1f;                            // GamePackets sub-op
        b[4] = 0x01;                            // LE16 prefix lo
        b[5] = 0x00;                            // LE16 prefix hi
        b[6] = 0x1f;                            // sub-tag EQUIP
        b[7] = (byte) (slot & 0xff);            // slot byte
        return b;
    }

    @Test
    public void parseSlotHolster() {
        // Retail-observed: slot=0x00 (12× — most common).
        EquipHolster p = new EquipHolster(buildBody(0x0042, 0x00));
        assertEquals(EquipHolster.SLOT_HOLSTER, p.parseSlot());
    }

    @Test
    public void parseSlotToolbeltSlot1() {
        EquipHolster p = new EquipHolster(buildBody(0x0042, 0x01));
        assertEquals(0x01, p.parseSlot());
    }

    @Test
    public void parseSlotToolbeltSlot2() {
        EquipHolster p = new EquipHolster(buildBody(0x0042, 0x02));
        assertEquals(0x02, p.parseSlot());
    }

    @Test
    public void parseSlotToolbeltSlot3Bitmask() {
        // 0x04 = slot 3 (bitmask form per retail observations).
        EquipHolster p = new EquipHolster(buildBody(0x0042, 0x04));
        assertEquals(0x04, p.parseSlot());
    }

    @Test
    public void parseSlotToolbeltSlot4Bitmask() {
        // 0x08 = slot 4.
        EquipHolster p = new EquipHolster(buildBody(0x0042, 0x08));
        assertEquals(0x08, p.parseSlot());
    }

    @Test
    public void parseSlotSentinel() {
        // 0xff observed once — sentinel/reset.
        EquipHolster p = new EquipHolster(buildBody(0x0042, 0xff));
        assertEquals(EquipHolster.SLOT_SENTINEL, p.parseSlot());
    }

    @Test
    public void parseSlotIsRepeatable() {
        // parseSlot resets the underlying ByteArrayInputStream
        // so it can be called multiple times safely.
        EquipHolster p = new EquipHolster(buildBody(0x0042, 0x08));
        assertEquals(0x08, p.parseSlot());
        assertEquals(0x08, p.parseSlot());
        assertEquals(0x08, p.parseSlot());
    }

    @Test
    public void executeRecogniseOnlyDoesNotThrow() {
        // Recognise-only handler: must not throw, must not require
        // a TCP connection, must not mutate Player state visibly.
        Player pl = PacketTestFixture.newPlayer();
        new EquipHolster(buildBody(0x0042, 0x00)).execute(pl);
        new EquipHolster(buildBody(0x0042, 0x01)).execute(pl);
        new EquipHolster(buildBody(0x0042, 0xff)).execute(pl);
        // Pass = no exception escaped.
    }

    @Test
    public void seqBytesAreIgnored() {
        // Slot parse must be insensitive to seq bytes.
        EquipHolster a = new EquipHolster(buildBody(0x0001, 0x02));
        EquipHolster b = new EquipHolster(buildBody(0xffff, 0x02));
        assertEquals(a.parseSlot(), b.parseSlot());
    }

    @Test
    public void constructorAcceptsValidBuffer() {
        // Smoke test — the constructor must accept the 8-byte
        // sub-packet without raising.
        assertNotNull(new EquipHolster(buildBody(0x0042, 0x00)));
    }
}
