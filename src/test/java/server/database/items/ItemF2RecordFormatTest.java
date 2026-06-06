package server.database.items;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Byte-format tests for the CharInfo section-5 (F2 inventory) per-item
 * record emitted by {@link Item#getItemInfoPacketData} with
 * {@link Item#PACKET_CHARINFOF2}.
 *
 * <p>Ground truth: retail CharInfo section 5 reassembled from
 * {@code RETAIL_ZONING_AND_ITEMS_LONG} (31 items) and
 * {@code RETAIL_HANNIBAL} (16 items). Every retail item record has the
 * shape
 * <pre>
 *   [block_len LE16][0e 0e][12-byte instance GUID][optional tail]
 * </pre>
 * after its type-specific prefix, where {@code block_len} counts the
 * bytes after the length field. A simple item (retail infobyte 0x21) is
 * 20 bytes of NetworkInfoData:
 * <pre>
 *   [tid LE16][21][cond][0e 00][0e 0e][12B GUID]
 * </pre>
 * Before the fix, Ceres emitted a 6-byte legacy record
 * ({@code [tid LE16][02][02][cond][maxcond]}) with NO instance-GUID
 * block; the NC2 client's CHARSYS parser then registered zero items and
 * the F2 grid rendered empty even though the item count was correct.
 */
public class ItemF2RecordFormatTest {

    private static short[] simpleTokens(int cond, int maxcond) {
        short[] t = new short[17];
        t[Item.TOKENS_CURRCOND] = (short) cond;
        t[Item.TOKENS_MAXCOND] = (short) maxcond;
        return t;
    }

    /**
     * The F2 wrapper around NetworkInfoData (see
     * {@link Item#getItemInfoPacketData}) is
     * {@code [len LE16 = nid.length+3][00][posX][posY][nid...]} but only
     * for PLINVENTORY containers. The unit-test {@link Item} has a null
     * container, so we assert the inner NetworkInfoData directly.
     */
    private static byte[] nid(Item it) throws Exception {
        java.lang.reflect.Field f = Item.class.getDeclaredField("NetworkInfoData");
        f.setAccessible(true);
        return (byte[]) f.get(it);
    }

    @Test
    public void simpleItem_hasInfobyte0x21AndInstanceGuidBlock() throws Exception {
        // tid 825 (Milky Ren) — a real Krafteo F2 SIMPLE item.
        Item it = new Item(825, 31L, null, Item.ITEMFLAG_SIMPLE,
                simpleTokens(255, 255));
        byte[] n = nid(it);

        assertNotNull(n);
        // [tid LE16][21][cond][0e 00][0e 0e][12B GUID] = 20 bytes
        assertEquals("simple F2 record is 20 bytes (retail 0x21 layout)",
                20, n.length);

        assertEquals("type_id LE16 low", 825 & 0xFF, n[0] & 0xFF);
        assertEquals("type_id LE16 high", (825 >> 8) & 0xFF, n[1] & 0xFF);
        assertEquals("infobyte = retail 0x21 (simple)", 0x21, n[2] & 0xFF);
        assertEquals("condition byte", 255, n[3] & 0xFF);

        // block_len LE16 = 0x000e = 14 (2 tag bytes + 12 GUID bytes)
        assertEquals("block_len low", 0x0e, n[4] & 0xFF);
        assertEquals("block_len high", 0x00, n[5] & 0xFF);
        // instance-id sub-TLV tag 0e 0e
        assertEquals("instance tag byte 0", 0x0e, n[6] & 0xFF);
        assertEquals("instance tag byte 1", 0x0e, n[7] & 0xFF);

        // 12-byte GUID present; must not be all-zero (client rejects).
        boolean nonZero = false;
        for (int i = 8; i < 20; i++) if (n[i] != 0) nonZero = true;
        assertTrue("instance GUID must be non-zero", nonZero);
    }

    @Test
    public void stackItem_hasInfobyte0x22AndInstanceGuidBlock() throws Exception {
        // tid 3381 (9mm Clip) — a real Krafteo F2 USES|STACK item.
        short[] t = simpleTokens(255, 255);
        t[Item.TOKENS_AMMOUSES] = 0; // -> retail "full" 0xffff encoding
        Item it = new Item(3381, 57L, null,
                Item.ITEMFLAG_USES | Item.ITEMFLAG_STACK, t);
        byte[] n = nid(it);

        // [tid LE16][22][02][ff ff][0e 00][0e 0e][12B GUID] = 22 bytes
        assertEquals("stack F2 record is 22 bytes (retail 0x22 layout)",
                22, n.length);
        assertEquals("infobyte = retail 0x22 (stack)", 0x22, n[2] & 0xFF);
        assertEquals("property marker 0x02", 0x02, n[3] & 0xFF);
        assertEquals("uses param low (full=0xff)", 0xFF, n[4] & 0xFF);
        assertEquals("uses param high (full=0xff)", 0xFF, n[5] & 0xFF);
        assertEquals("block_len low", 0x0e, n[6] & 0xFF);
        assertEquals("instance tag byte 0", 0x0e, n[8] & 0xFF);
        assertEquals("instance tag byte 1", 0x0e, n[9] & 0xFF);
    }

    @Test
    public void distinctItems_getDistinctGuids() throws Exception {
        byte[] a = nid(new Item(825, 31L, null, Item.ITEMFLAG_SIMPLE,
                simpleTokens(255, 255)));
        byte[] b = nid(new Item(825, 32L, null, Item.ITEMFLAG_SIMPLE,
                simpleTokens(255, 255)));
        // same type, same cond, different DB id -> GUID bytes must differ
        boolean differ = false;
        for (int i = 8; i < 20; i++) if (a[i] != b[i]) differ = true;
        assertTrue("items with different DB ids must get different GUIDs",
                differ);
    }

    @Test
    public void sameItem_stableGuidAcrossRebuild() throws Exception {
        byte[] a = nid(new Item(825, 31L, null, Item.ITEMFLAG_SIMPLE,
                simpleTokens(255, 255)));
        byte[] b = nid(new Item(825, 31L, null, Item.ITEMFLAG_SIMPLE,
                simpleTokens(255, 255)));
        org.junit.Assert.assertArrayEquals(
                "same item id must produce a stable GUID across redeliveries",
                a, b);
    }
}
