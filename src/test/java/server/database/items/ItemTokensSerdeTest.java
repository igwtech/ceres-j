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
