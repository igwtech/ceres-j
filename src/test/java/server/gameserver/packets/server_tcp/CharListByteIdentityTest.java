package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.LinkedList;

import org.junit.Before;
import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.PlayerCharacterManager;
import server.networktools.PacketBuilderTCP;

/**
 * Byte-identity tests for {@link CharList} (TCP S→C 0x8385).
 *
 * <p>Pins the post-fix bytes against the catalog's modal value
 * for body offsets [2..3]. Catalog evidence: 27 retail samples
 * across 17 captures with 17 distinct values at this slot —
 * variable session-state, not a fixed signature. The previous
 * {@code fe 02} value was over-generalized from a single pcap.
 * {@code 00 00} is the most common retail value (5/27 = 18.5 %)
 * and matches what legacy Ceres-J always emitted.
 *
 * <p>The dummy-slot encoding is well-understood and pinned here
 * directly. The per-character variable layout is exercised by
 * the existing {@link CharListTest} and {@code CharSelectFlow}
 * tests; this test focuses on the framing + dummy slots which
 * are 100% deterministic.
 */
public class CharListByteIdentityTest {

    @Before
    public void initPcManager() throws Exception {
        // CharList → PlayerCharacterManager.getCharacter requires
        // pcList to be initialised; PlayerCharacterManager.init()
        // touches the DB. Install an empty list directly.
        Field f = PlayerCharacterManager.class.getDeclaredField("pcList");
        f.setAccessible(true);
        if (f.get(null) == null) {
            f.set(null, new LinkedList<PlayerCharacter>());
        }
    }

    private static byte[] wireBytes(PacketBuilderTCP pkt) {
        byte[] data = pkt.getData();
        int n = pkt.size();
        byte[] sliced = new byte[n];
        System.arraycopy(data, 0, sliced, 0, n);
        return sliced;
    }

    @Test
    public void emptyAccountEmitsFourDummySlots() {
        // An account with all four char slots = 0 (no characters)
        // produces a fully-deterministic CharList: header + 4×41B
        // dummy records.
        Account empty = new Account(0);
        empty.setUsername("test");
        // Account default char[0..3] are 0 by virtue of the array init.
        byte[] data = wireBytes(new CharList(empty));

        // FE-frame envelope at [0..2]
        assertEquals((byte) 0xfe, data[0]);
        // Body length: 8B header + 4 × 41B dummy records = 172B
        int bodyLen = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        assertEquals("body length must be 172 (8B header + 164B chars)",
                172, bodyLen);

        // Opcode at body[0..1]
        assertEquals((byte) 0x83, data[3]);
        assertEquals((byte) 0x85, data[4]);

        // Bytes [2..3] of body — the session-state slot. We emit
        // `00 00` (catalog modal value).
        assertEquals("body[2] = 0x00 (modal)", 0x00, data[5] & 0xFF);
        assertEquals("body[3] = 0x00 (modal)", 0x00, data[6] & 0xFF);

        // numchars LE16 = 4
        assertEquals(0x04, data[7] & 0xFF);
        assertEquals(0x00, data[8] & 0xFF);

        // charsize LE16 = 0x29 (41 bytes)
        assertEquals(0x29, data[9] & 0xFF);
        assertEquals(0x00, data[10] & 0xFF);
    }

    @Test
    public void dummySlotPaddingExactBytes() {
        // The 41-byte CHARDUMMY pattern is well-known and
        // deterministic. Pin it so a refactor of the dummy
        // constant cannot regress.
        Account empty = new Account(0);
        empty.setUsername("test");
        byte[] data = wireBytes(new CharList(empty));

        // First dummy slot starts at body offset 8 → wire offset 11.
        int dummyStart = 11;
        // Leading 4 bytes of dummy record: `ff ff ff ff` (id = -1)
        assertEquals((byte) 0xff, data[dummyStart + 0]);
        assertEquals((byte) 0xff, data[dummyStart + 1]);
        assertEquals((byte) 0xff, data[dummyStart + 2]);
        assertEquals((byte) 0xff, data[dummyStart + 3]);
        // Bytes [4..24] are zeros
        for (int i = 4; i <= 24; i++) {
            assertEquals("dummy[" + i + "] zero",
                    0, data[dummyStart + i] & 0xFF);
        }
        // Bytes [25..29] are five 0x01 bytes (per CHARDUMMY)
        for (int i = 25; i <= 29; i++) {
            assertEquals("dummy[" + i + "] = 0x01",
                    0x01, data[dummyStart + i] & 0xFF);
        }
        // Byte [30] = 0x21 per CHARDUMMY constant
        assertEquals(0x21, data[dummyStart + 30] & 0xFF);
        // Byte [35] = 0x01
        assertEquals(0x01, data[dummyStart + 35] & 0xFF);
    }

    @Test
    public void allFourDummySlotsAreIdentical() {
        // The 41-byte dummy pattern repeats verbatim for each
        // empty char slot.
        Account empty = new Account(0);
        empty.setUsername("test");
        byte[] data = wireBytes(new CharList(empty));

        int firstStart = 11;
        for (int slot = 1; slot < 4; slot++) {
            int slotStart = firstStart + slot * 41;
            for (int i = 0; i < 41; i++) {
                assertEquals("slot " + slot + " offset " + i
                        + " must match slot 0",
                        data[firstStart + i],
                        data[slotStart + i]);
            }
        }
    }

    @Test
    public void bytesNotFE02NoLongerEmitted() {
        // Regression: the previous Ceres-J emitter wrote `fe 02`
        // at body[2..3]. Confirm we no longer do that. A future
        // refactor that resurrects the wrong bytes will fail here.
        Account empty = new Account(0);
        empty.setUsername("test");
        byte[] data = wireBytes(new CharList(empty));
        assertNotEquals("body[2] must not be 0xfe — that was the "
                + "incorrect over-generalised value pre-2026-05-08",
                (byte) 0xfe, data[5]);
        // 0x02 is fine on its own; the pair `fe 02` is the
        // specific anti-pattern.
    }
}
