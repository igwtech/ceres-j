package server.gameserver.npc;

import java.util.Arrays;

/**
 * Sub-action / record-shape aware parser for the inner body of
 * {@code UDP S->C 0x03/0x2d} (the NPC / entity event multiplexer).
 *
 * <h3>Task #167 finding — the layout is NOT per-sub-action</h3>
 *
 * <p>The packet doc historically assumed every distinct
 * {@code (category, sub-action)} pair carried its own body layout
 * (the "440 distinct layouts" hypothesis). Sweeping all 17 retail
 * captures (69,755 observations) disproves that: the body shape is
 * selected by <b>length</b> and the <b>record-discriminator byte at
 * offset 5</b> ({@code 0x71} / {@code 0x75}), not by the sub-action.
 * The sub-action byte at offset 1 is an <i>event tag</i> (which mob
 * event fired) and the category LE16 at [2..3] is a routing class;
 * neither changes the field grid past offset 5.
 *
 * <p>Body offsets below are relative to the {@code 0x2d} sub-opcode
 * byte (offset 0) — i.e. the same frame the packet doc and
 * {@link MobDataDecoder} use. {@link MobDataDecoder} is the legacy
 * fixed-grid parser kept for back-compat; this class is the
 * retail-pinned, evidence-cited replacement.
 *
 * <h3>Three proven forms</h3>
 *
 * <pre>
 *   6-byte   : 2d [sub] [cat LE16] 00 06
 *              minimal event ping — no body. ~400 samples across
 *              9 captures, many distinct sub-actions, cat=0x0001.
 *
 *   10-byte  : 2d [sub] 03 00 00 0a 00 [token LE32]
 *              short event w/ one trailing token. Two seen tokens:
 *              00000000 (null) and 601f45.. (a recurring value).
 *              cat=0x0003. ~700 combined samples.
 *
 *   55-byte  : full record, shape by byte[5]:
 *
 *     byte[5]=0x71  ENTITY world-state  (26,755 retail obs)
 *       [0]      0x2d
 *       [1]      sub-action (event tag)
 *       [2..3]   category LE16
 *       [4]      0x00
 *       [5]      0x71 record discriminator (== MobState.COMBAT byte)
 *       [6]      route / sub-class (0x20 = position-bearing)
 *       [7..10]  float posX (when route=0x20)  /  LE32 handle
 *       [11..14] 0xFFFFFFFF "no-target" sentinel  (97.7% of obs)
 *       [15..18] float f1
 *       [19..22] float f2
 *       [23..26] float f3
 *       [27..30] float f4
 *       [31..34] float f5  ==  f2  (posY echo — structural proof,
 *                                   62.7% / 100% of clean subset)
 *       [35]     float-block high byte (0x45 / 0xc5)
 *       [36..47] INVARIANT 12 B = 43 00 00 80 06 00 00 00 01 00 00 00
 *                                   (byte-identical across captures)
 *       [48..51] tail marker = 81 ca 09 00  (fixed offset, 16,681 obs)
 *       [52..54] 3-byte per-record tail
 *
 *     byte[5]=0x75  LOCAL / relative-state  (36,977 retail obs)
 *       [0..6]   as above, [5]=0x75 (== MobState.IDLE byte)
 *       [7..54]  a chain of LE32 entity handles (each ends 2f 01 /
 *                ...0c) interleaved with the recurring markers
 *                2e ce 2c 00 @~[32] and the handle-chain signature
 *                f8 f8 2f 01 89 01 1e 00 @~[44..51]. Field grid is
 *                handle-list shaped, NOT world-float shaped; the
 *                individual slot meanings are still
 *                insufficient-evidence and held opaque.
 * </pre>
 *
 * <p>Evidence: every figure above is from
 * {@code tools/extract_2d_layouts.py} + {@code verify_2d_record71.py}
 * over the 17 retail captures (excludes the two Ceres-J test pcaps and
 * the {@code RETAIL_RETAIL_PLAZA_CROSSZONE_20260514} capture not in the
 * catalog). See {@code docs/protocol/packets/udp_s2c_03_2d.md}.
 */
public final class Npc2dRecordDecoder {

    public static final int LEN_PING   = 6;
    public static final int LEN_SHORT  = 10;
    public static final int LEN_RECORD = 55;

    /** Record-discriminator byte at offset 5. */
    public static final int DISC_ENTITY = 0x71;  // == MobState.COMBAT
    public static final int DISC_LOCAL  = 0x75;  // == MobState.IDLE

    /** The 12-byte invariant block at [36..47], byte-identical across
     *  every retail capture for the 0x71 record. */
    public static final byte[] INVARIANT_BLOCK = {
        0x43, 0x00, 0x00, (byte) 0x80, 0x06, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x00, 0x00
    };

    /** Tail marker at [48..51] of the 0x71 record. */
    public static final byte[] TAIL_MARKER = {
        (byte) 0x81, (byte) 0xca, 0x09, 0x00
    };

    /** "no current target" sentinel at [11..14] of the 0x71 record. */
    public static final int NO_TARGET = 0xFFFFFFFF;

    public enum Form { PING, SHORT, ENTITY_RECORD, LOCAL_RECORD, UNKNOWN }

    private Npc2dRecordDecoder() {}

    public static final class Decoded {
        public final Form form;
        public final int subAction;     // [1] event tag
        public final int category;      // [2..3] LE16 routing class
        public final int recordDisc;    // [5] (records only; -1 else)
        public final int route;         // [6] (records only; -1 else)

        // ── ENTITY_RECORD (byte[5]=0x71) fields ──
        public final float posX;        // [7..10]  (route 0x20)
        public final int   secondRef;   // [11..14] LE32 (sentinel/handle)
        public final float f1, f2, f3, f4, f5;  // [15..34]
        public final boolean invariantOk;       // [36..47] == block
        public final boolean tailMarkerOk;      // [48..51] == marker
        /** [52..54] 3-byte per-record tail. */
        public final byte[] tail;

        // ── SHORT form token ──
        public final int shortToken;    // [6..9] LE32 (SHORT only)

        /** Raw body bytes 7..54 for LOCAL_RECORD (handle-chain shape;
         *  individual slots are insufficient-evidence). */
        public final byte[] localBody;

        Decoded(Form form, int subAction, int category, int recordDisc,
                int route, float posX, int secondRef, float f1, float f2,
                float f3, float f4, float f5, boolean invariantOk,
                boolean tailMarkerOk, byte[] tail, int shortToken,
                byte[] localBody) {
            this.form = form;
            this.subAction = subAction;
            this.category = category;
            this.recordDisc = recordDisc;
            this.route = route;
            this.posX = posX;
            this.secondRef = secondRef;
            this.f1 = f1; this.f2 = f2; this.f3 = f3;
            this.f4 = f4; this.f5 = f5;
            this.invariantOk = invariantOk;
            this.tailMarkerOk = tailMarkerOk;
            this.tail = tail;
            this.shortToken = shortToken;
            this.localBody = localBody;
        }

        public boolean hasTarget() {
            return form == Form.ENTITY_RECORD && secondRef != NO_TARGET;
        }
        /** True when f5 echoes f2 (the posY-echo structural marker
         *  identifying a position-bearing 0x71 record). */
        public boolean isPositionBearing() {
            return form == Form.ENTITY_RECORD
                    && Float.floatToRawIntBits(f5)
                       == Float.floatToRawIntBits(f2);
        }
    }

    /**
     * Decode an inner body whose offset 0 is the {@code 0x2d}
     * sub-opcode byte. Returns a {@link Decoded} for every recognised
     * form; {@code form == UNKNOWN} for lengths/discriminators we have
     * not pinned (caller logs + drops without data loss).
     */
    public static Decoded decode(byte[] b) {
        if (b == null || b.length < 2 || (b[0] & 0xff) != 0x2d) {
            return unknown(b);
        }
        int sub = b[1] & 0xff;
        int cat = (b.length >= 4)
                ? (b[2] & 0xff) | ((b[3] & 0xff) << 8) : -1;

        if (b.length == LEN_PING) {
            return new Decoded(Form.PING, sub, cat, -1, -1, 0f,
                    0, 0f, 0f, 0f, 0f, 0f, false, false,
                    new byte[0], 0, new byte[0]);
        }
        if (b.length == LEN_SHORT) {
            int tok = leInt(b, 6);
            return new Decoded(Form.SHORT, sub, cat, -1, -1, 0f,
                    0, 0f, 0f, 0f, 0f, 0f, false, false,
                    new byte[0], tok, new byte[0]);
        }
        if (b.length == LEN_RECORD) {
            int disc = b[5] & 0xff;
            int route = b[6] & 0xff;
            if (disc == DISC_ENTITY) {
                float posX = Float.intBitsToFloat(leInt(b, 7));
                int second = leInt(b, 11);
                float f1 = Float.intBitsToFloat(leInt(b, 15));
                float f2 = Float.intBitsToFloat(leInt(b, 19));
                float f3 = Float.intBitsToFloat(leInt(b, 23));
                float f4 = Float.intBitsToFloat(leInt(b, 27));
                float f5 = Float.intBitsToFloat(leInt(b, 31));
                boolean inv = Arrays.equals(
                        Arrays.copyOfRange(b, 36, 48), INVARIANT_BLOCK);
                boolean tm = Arrays.equals(
                        Arrays.copyOfRange(b, 48, 52), TAIL_MARKER);
                byte[] tail = Arrays.copyOfRange(b, 52, 55);
                return new Decoded(Form.ENTITY_RECORD, sub, cat, disc,
                        route, posX, second, f1, f2, f3, f4, f5,
                        inv, tm, tail, 0, new byte[0]);
            }
            if (disc == DISC_LOCAL) {
                byte[] lb = Arrays.copyOfRange(b, 7, 55);
                return new Decoded(Form.LOCAL_RECORD, sub, cat, disc,
                        route, 0f, 0, 0f, 0f, 0f, 0f, 0f,
                        false, false, new byte[0], 0, lb);
            }
        }
        return unknown(b);
    }

    private static Decoded unknown(byte[] b) {
        int sub = (b != null && b.length >= 2) ? b[1] & 0xff : -1;
        int cat = (b != null && b.length >= 4)
                ? (b[2] & 0xff) | ((b[3] & 0xff) << 8) : -1;
        return new Decoded(Form.UNKNOWN, sub, cat, -1, -1, 0f, 0,
                0f, 0f, 0f, 0f, 0f, false, false, new byte[0], 0,
                new byte[0]);
    }

    private static int leInt(byte[] a, int off) {
        return  (a[off]     & 0xff)
             | ((a[off + 1] & 0xff) << 8)
             | ((a[off + 2] & 0xff) << 16)
             | ((a[off + 3] & 0xff) << 24);
    }
}
