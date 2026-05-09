package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Reliable {@code 0x03→0x1f→01 00 25 13 …} 41-byte receipt observed
 * once during a retail vendor-buy on 2026-04-26 — arrived
 * simultaneously with the {@code 1f 01 00 25 19 [LE32]×5} unreliable
 * snapshot. Suspect carrier of the HUD cash readout (the 0x19
 * snapshot was empirically ruled out the same day).
 *
 * <p>Inner body (37 bytes after the `01 00 25 13` prefix):
 * <pre>
 *   d2 2f 04                   3 bytes  — likely 24-bit entity local-id
 *   e3 15 08 00                LE32     — candidate cash field A (530915)
 *   d3 2f 18 03                LE32     — candidate cash field B (51,847,635)
 *   ff ff 14 00                4 bytes  — sentinel + LE16 (20)
 *   db 05                      LE16     — 1499
 *   21 c8 0e 00                LE32     — candidate cash field C (968,225)
 *   0e 0e 4d 3d f7 3e 57 57    8 bytes  — item-GUID part 1
 *   d3 44 a3 97 51 c9 1e 00    8 bytes  — item-GUID part 2 (final LE32 = 2,017,617)
 * </pre>
 *
 * <p>For now this class can either ship the original retail bytes
 * verbatim (for an apples-to-apples first test) or substitute a
 * chosen LE32 into one of the candidate slots.
 */
public class CashReceipt extends PacketBuilderUDP1303 {

    /** Candidate cash field offsets, as indices into {@link #RETAIL_BODY}
     *  (the 37-byte trailer that follows the {@code 01 00 25 13}
     *  prefix). The substitution constructor writes a 4-byte LE32 at
     *  these positions. The previous constants were inconsistently
     *  shifted (some trailer-relative, some inner-body-relative); fixed
     *  here so each constant points at the actual LE32 field its
     *  comment describes. */
    public static final int FIELD_A_OFFSET_530K  = 3;   // e3 15 08 00 → 530,915
    public static final int FIELD_B_OFFSET_51M   = 7;   // d3 2f 18 03 → 51,847,635
    public static final int FIELD_C_OFFSET_968K  = 17;  // 21 c8 0e 00 → 968,225
    public static final int FIELD_D_OFFSET_2M    = 33;  // 51 c9 1e 00 → 2,017,617

    /** Original retail bytes captured 2026-04-26 (37 bytes after `01 00 25 13`). */
    private static final byte[] RETAIL_BODY = {
        (byte)0xd2, (byte)0x2f, (byte)0x04,                                  // local-id 3B
        (byte)0xe3, (byte)0x15, (byte)0x08, (byte)0x00,                      // field A LE32
        (byte)0xd3, (byte)0x2f, (byte)0x18, (byte)0x03,                      // field B LE32
        (byte)0xff, (byte)0xff, (byte)0x14, (byte)0x00,                      // sentinel + 20
        (byte)0xdb, (byte)0x05,                                              // 1499
        (byte)0x21, (byte)0xc8, (byte)0x0e, (byte)0x00,                      // field C LE32
        (byte)0x0e, (byte)0x0e, (byte)0x4d, (byte)0x3d,                      // GUID part 1
        (byte)0xf7, (byte)0x3e, (byte)0x57, (byte)0x57,                      // GUID part 2
        (byte)0xd3, (byte)0x44, (byte)0xa3, (byte)0x97,                      // GUID part 3
        (byte)0x51, (byte)0xc9, (byte)0x1e, (byte)0x00                       // field D LE32
    };

    /** Send the retail bytes verbatim (no field substitution). */
    public CashReceipt(Player pl) {
        super(pl);
        write(0x1f);
        write(0x01);
        write(0x00);
        write(0x25);
        write(0x13);
        write(RETAIL_BODY);
    }

    /**
     * Send the retail body with one LE32 slot overwritten by {@code value}.
     * Used to bisect which of the four candidate LE32 fields drives the HUD.
     */
    public CashReceipt(Player pl, int fieldOffset, int value) {
        super(pl);
        write(0x1f);
        write(0x01);
        write(0x00);
        write(0x25);
        write(0x13);
        byte[] body = RETAIL_BODY.clone();
        body[fieldOffset]     = (byte)(value & 0xff);
        body[fieldOffset + 1] = (byte)((value >> 8) & 0xff);
        body[fieldOffset + 2] = (byte)((value >> 16) & 0xff);
        body[fieldOffset + 3] = (byte)((value >> 24) & 0xff);
        write(body);
    }
}
