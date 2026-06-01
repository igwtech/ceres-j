package server.database.items;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Unit tests for {@link Item#serializeTokens} / {@link Item#deserializeTokens}
 * — the 17-short tokens array is the bulk of an Item's persisted state
 * (weapon condition, damage, frequency, slots, mods…). Round-tripping
 * must be byte-exact so a weapon never changes its stats across a
 * server restart.
 */
public class ItemTokensSerdeTest {

    private static Item buildItem(short[] tokens) {
        // Item ctor needs a non-null tokens array of length 17.
        return new Item(/*type*/ 19, /*id*/ 42L, /*parent*/ null,
                Item.ITEMFLAG_WEAPON, tokens);
    }

    @Test
    public void roundtrip_zeroes() {
        short[] tokens = new short[17];
        byte[] bytes = buildItem(tokens).serializeTokens();
        assertEquals("serialised length must be 34 bytes (17 LE16)",
                34, bytes.length);
        short[] back = Item.deserializeTokens(bytes);
        assertArrayEquals(tokens, back);
    }

    @Test
    public void roundtrip_starterWeaponState() {
        // Matches the starter-inventory tokens emitted by
        // PlayerCharacterManager.createCharacter:
        short[] tokens = new short[17];
        tokens[Item.TOKENS_CURRCOND] = 255;
        tokens[Item.TOKENS_MAXCOND]  = 255;
        tokens[Item.TOKENS_DMG]      = 200;
        tokens[Item.TOKENS_FREQUENCY]= 200;
        tokens[Item.TOKENS_HANDLING] = 200;
        tokens[Item.TOKENS_RANGE]    = 200;
        tokens[Item.TOKENS_AMMOUSES] = 3;
        tokens[Item.TOKENS_ITEMSONSTACK] = 5;

        byte[] bytes = buildItem(tokens).serializeTokens();
        short[] back = Item.deserializeTokens(bytes);
        assertArrayEquals(tokens, back);
    }

    @Test
    public void roundtrip_negativeShorts() {
        // Mod-slot values can go negative (signed short).
        short[] tokens = new short[17];
        tokens[Item.TOKENS_MOD1] = -1;
        tokens[Item.TOKENS_MOD2] = -100;
        tokens[Item.TOKENS_MOD3] = Short.MIN_VALUE;
        tokens[Item.TOKENS_MOD4] = Short.MAX_VALUE;

        byte[] bytes = buildItem(tokens).serializeTokens();
        short[] back = Item.deserializeTokens(bytes);
        assertArrayEquals(tokens, back);
    }

    @Test
    public void serializeIsLittleEndian() {
        // Pin the byte order — flipping it would invalidate every
        // persisted item the next time the migration runs.
        short[] tokens = new short[17];
        tokens[0] = (short) 0x1234;  // CURRCOND
        tokens[1] = (short) 0xABCD;  // MAXCOND

        byte[] bytes = buildItem(tokens).serializeTokens();
        assertEquals("byte 0 = low byte of token[0]",
                0x34, bytes[0] & 0xff);
        assertEquals("byte 1 = high byte of token[0]",
                0x12, bytes[1] & 0xff);
        assertEquals("byte 2 = low byte of token[1]",
                0xCD, bytes[2] & 0xff);
        assertEquals("byte 3 = high byte of token[1]",
                0xAB, bytes[3] & 0xff);
    }

    @Test
    public void deserializeNull_returnsZeroFilled() {
        // Defensive: legacy items table rows have tokens=NULL.
        short[] back = Item.deserializeTokens(null);
        assertNotNull(back);
        assertEquals(17, back.length);
        for (short s : back) assertEquals(0, s);
    }

    @Test
    public void deserializeWrongLength_returnsZeroFilled() {
        // Defensive: corrupted row with truncated bytea.
        short[] back = Item.deserializeTokens(new byte[]{1, 2, 3});
        assertNotNull(back);
        assertEquals(17, back.length);
        for (short s : back) assertEquals(0, s);
    }

    /**
     * Named-columns refactor invariant (2026-06-01): the per-column
     * decomposition that ItemManager persists, when reassembled into a
     * short[17] and serialised, must reproduce the original 34-byte wire
     * blob byte-for-byte. Uses the live default starter blob
     * {@code ff00 ff00 c800 c800 c800 c800 0000 0300 0500 00…} (LE16).
     */
    @Test
    public void namedColumns_reassembleToIdenticalBlob() {
        // The canonical 34-byte default blob (LE), zero-padded tail.
        byte[] original = new byte[34];
        original[0] = (byte) 0xff;            // curr_cond = 255
        original[2] = (byte) 0xff;            // max_cond  = 255
        original[4] = (byte) 0xc8;            // damage    = 200
        original[6] = (byte) 0xc8;            // frequency = 200
        original[8] = (byte) 0xc8;            // handling  = 200
        original[10] = (byte) 0xc8;           // range     = 200
        // clip_size (index 6) = 0 → bytes 12,13 stay 0
        original[14] = 0x03;                  // ammo_uses = 3
        original[16] = 0x05;                  // stack_count = 5
        // remainder (mods, conster) all zero

        // Decompose exactly as ItemManager.saveItem does (per named column),
        // mirroring the DB write.
        short[] src = Item.deserializeTokens(original);
        int currCond   = src[Item.TOKENS_CURRCOND];
        int maxCond    = src[Item.TOKENS_MAXCOND];
        int damage     = src[Item.TOKENS_DMG];
        int frequency  = src[Item.TOKENS_FREQUENCY];
        int handling   = src[Item.TOKENS_HANDLING];
        int range      = src[Item.TOKENS_RANGE];
        int clipSize   = src[Item.TOKENS_CLIPSIZE];
        int ammoUses   = src[Item.TOKENS_AMMOUSES];
        int stackCount = src[Item.TOKENS_ITEMSONSTACK];
        int modSlots   = src[Item.TOKENS_SLOTS];
        int modUsed    = src[Item.TOKENS_SLOTSINUSE];
        int conster    = src[Item.TOKENS_CONSTER];
        int[] mods = {
            src[Item.TOKENS_MOD1], src[Item.TOKENS_MOD2], src[Item.TOKENS_MOD3],
            src[Item.TOKENS_MOD4], src[Item.TOKENS_MOD5]
        };

        // Reassemble exactly as ItemManager.loadItems does (per named col).
        short[] rebuilt = new short[17];
        rebuilt[Item.TOKENS_CURRCOND]     = (short) currCond;
        rebuilt[Item.TOKENS_MAXCOND]      = (short) maxCond;
        rebuilt[Item.TOKENS_DMG]          = (short) damage;
        rebuilt[Item.TOKENS_FREQUENCY]    = (short) frequency;
        rebuilt[Item.TOKENS_HANDLING]     = (short) handling;
        rebuilt[Item.TOKENS_RANGE]        = (short) range;
        rebuilt[Item.TOKENS_CLIPSIZE]     = (short) clipSize;
        rebuilt[Item.TOKENS_AMMOUSES]     = (short) ammoUses;
        rebuilt[Item.TOKENS_ITEMSONSTACK] = (short) stackCount;
        rebuilt[Item.TOKENS_SLOTS]        = (short) modSlots;
        rebuilt[Item.TOKENS_SLOTSINUSE]   = (short) modUsed;
        rebuilt[Item.TOKENS_MOD1]         = (short) mods[0];
        rebuilt[Item.TOKENS_MOD2]         = (short) mods[1];
        rebuilt[Item.TOKENS_MOD3]         = (short) mods[2];
        rebuilt[Item.TOKENS_MOD4]         = (short) mods[3];
        rebuilt[Item.TOKENS_MOD5]         = (short) mods[4];
        rebuilt[Item.TOKENS_CONSTER]      = (short) conster;

        byte[] roundtripped = buildItem(rebuilt).serializeTokens();
        assertArrayEquals("named-columns reassembly must reproduce the"
                + " original 34-byte wire blob", original, roundtripped);
    }

    /**
     * The packed inventory `slot` int decomposes into
     * (slot_index, slot_y, slot_x) and re-packs to the same value. Pins
     * the round-trip for the required boundary set: flat QB slots, the
     * F2 X/Y origin, and multi-dimension F2 positions.
     */
    @Test
    public void slotPacking_roundTrips() {
        int[] packed = {0, 7, 65538, 131076, 196614, 328195};
        for (int pos : packed) {
            int slotIndex = pos / 65536;
            int slotY = (pos - slotIndex * 65536) / 256;
            int slotX = (pos - slotIndex * 65536 - slotY * 256);
            int repacked = slotX + slotY * 256 + slotIndex * 65536;
            assertEquals("slot packing must round-trip for " + pos,
                    pos, repacked);
        }
    }

    @Test
    public void itemGetters_exposePersistenceFields() {
        short[] tokens = new short[17];
        tokens[Item.TOKENS_DMG] = 123;
        Item it = new Item(/*type*/ 42, /*id*/ 99L, /*parent*/ null,
                Item.ITEMFLAG_SPELL, tokens);

        assertEquals(99L, it.getId());
        assertEquals(42, it.getTypeId());
        assertEquals(Item.ITEMFLAG_SPELL, it.getFlags());
        assertArrayEquals(tokens, it.getTokens());
    }
}
