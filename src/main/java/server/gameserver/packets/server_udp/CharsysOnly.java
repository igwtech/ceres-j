package server.gameserver.packets.server_udp;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.interfaces.ServerUDPPacket;
import server.networktools.PacketBuilderUDP1303;

/**
 * Multi-fragment {@code 0x03→0x07} CharsysInfo update with discriminator
 * {@code 0x02} (the CHARSYS path, distinct from the CharInfo {@code 0x01}
 * path used by {@link CharInfo}).
 *
 * <p><b>DO NOT USE FOR STATE THAT MUST APPLY ON THE CLIENT (task #191).</b>
 * The {@code 0x03/0x07} disc-{@code 0x02} multipart maps to client UI
 * event {@code 0xa8}, which is a <em>no-op QUERY</em> — the client
 * acknowledges the buffer but NEVER runs the CHARSYS TLV parser
 * ({@code FUN_008447d0}) nor the FullCharsysInfo recompute
 * ({@code FUN_0080b8b0}), so HP/PSI/STA/cash/subskills the buffer
 * carries are silently discarded. The disc-{@code 0x01} path only fires
 * getters ({@code 0xa7}/{@code 0x13ef}). The dedicated single-packet
 * CHARSYS handler that fires the applying event {@code 0x6e}
 * (vtable-B slot 8 → {@code FUN_00841dc0} → {@code FUN_008033d0} →
 * {@code FUN_008447d0} → {@code FUN_0080b8b0}) has no pinned server-side
 * opcode in the available spec, and case {@code 0xb3} is gated/dead in
 * the Evolution build (project memory {@code charsys_dead_code}).
 *
 * <p>The proven server-side levers that DO apply are used instead by
 * every user-visible GM command:
 * <ul>
 *   <li>HP/PSI/STA → {@link PoolUpdate} ({@code 0x1f 01 00 50}
 *       signed-delta, retail death-capture verified) +
 *       {@link PoolStatusBroadcast}.</li>
 *   <li>Subskills / pool maxima → {@link ForcedZoning} full-CharInfo
 *       redelivery (project memory {@code hud_pool_path_confirmed}).</li>
 *   <li>Cash → {@link CashUpdate} ({@code 0x03/0x1f → 0x25 0x13 → 0x04},
 *       retail HUD-screenshot verified, project memory
 *       {@code cash_and_falldamage_subops}).</li>
 *   <li>Soullight → {@link SoullightUpdate}
 *       ({@code 0x02/0x1f → 0x25 0x1f} float, retail verified).</li>
 * </ul>
 *
 * <p>This class is retained only as a reverse-engineering harness
 * primitive (see {@code ResourceProbeEvent}); it is {@code @Deprecated}
 * to flag that wiring it into a runtime command path is a known bug.
 *
 * <p>Ghidra trace (2026-04-26):
 * <pre>
 *   network handler -> FUN_00841dc0 (vtable B slot 8)
 *                   -> FUN_008033d0 ("CHARSYS : Buffer loaded, buffer size: %i")
 *                   -> FUN_008447d0  (loop the buffer)
 *                       FUN_00845400  (parse section header [id 1B][len LE2][data])
 *                         case 2: FUN_00845820  (POOLS — HP/PSI/STA/synaptic/soullight)
 *                         case 8: FUN_00846470  (Cash + GRs + buddies)
 *                   -> FUN_0080b8b0  (FullCharsysInfo recompute, fires HUD events)
 * </pre>
 *
 * <p>The buffer can contain ANY subset of sections (1, 2, 3, 4, 5, 6, 7, 8,
 * 9, 10, 11, 12). For a focused HUD update we only emit section 2 + 8.
 *
 * <p>Section 2 (Pools) wire layout (verified from FUN_00845820 decompile):
 * <pre>
 *   [num_pools 1B][stride=4 1B]
 *   per pool (×num_pools): [cur LE2][max LE2]   // pool[0]=HP, pool[1]=PSI, pool[2]=STA, pool[3]=?
 *   [unk1 LE2][unk2 LE2][unk3 LE2]
 *   [synaptic 1B]   // 0..100, sets +0x448 / +0x440 / +0x444
 *   [soullight 1B]  // signed: wire = 128 + value, value range −128..127, written to +0x454 as float
 *   [unk4 LE2]      // optional, written to +0x404
 * </pre>
 *
 * <p>Section 8 (Cash) wire layout starts with {@code 0x0a uint32_cash …}
 * — see {@link CharInfo} Section 8 for the full structure; here we keep it
 * minimal.
 */
@Deprecated
public class CharsysOnly implements ServerUDPPacket {

    private static final int CHUNK_BYTES = 220 - 6; // multipart 6-byte header

    /**
     * Multipart discriminator. 0x02 = CharsysInfo path (fires UI event 0xa8).
     * 0x01 = CharInfo path which fires:
     *   - 0xa7 with 0x45 bytes (only first time bit 0 unset)
     *   - 0x13ef with buffer past offset 0x48 (every time IF size > 0x42)
     * Use 0x01 + a 72-byte filler prefix to hit the 0x13ef-only path on
     * subsequent sends without re-firing the guarded 0xa7 event.
     */
    public static final int DISC_CHARSYS = 0x02;
    public static final int DISC_CHARINFO = 0x01;

    /**
     * Chain key for the multipart reassembler. Login's {@code CharInfo}
     * uses {@code 0x00}; we increment per-call so each runtime CharsysInfo
     * starts a fresh reassembly chain (otherwise the parser would silently
     * append our fragments to the long-closed login chain).
     */
    private static int chainKeyCounter = 1;

    private final Player pl;
    private final byte[] payload;
    private final int chainKey;
    private final int discriminator;

    public CharsysOnly(Player pl, byte[] payload) {
        this(pl, payload, DISC_CHARSYS);
    }

    public CharsysOnly(Player pl, byte[] payload, int discriminator) {
        this.pl = pl;
        this.payload = payload;
        this.chainKey = chainKeyCounter++ & 0xFF;
        this.discriminator = discriminator;
    }

    /** Convenience: build a payload with section 2 (pools) only. */
    public static byte[] poolsOnly(Player pl, int hpCur, int hpMax,
                                   int psiCur, int psiMax,
                                   int staCur, int staMax,
                                   int soullight) {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        sec.write(4);  // num pools = 4
        sec.write(4);  // stride
        write16(sec, hpCur);  write16(sec, hpMax);
        write16(sec, psiCur); write16(sec, psiMax);
        write16(sec, staCur); write16(sec, staMax);
        write16(sec, 255);    write16(sec, 255);
        write16(sec, 0);      // unk1
        write16(sec, 0);      // unk2
        write16(sec, 0);      // unk3
        sec.write(100);                          // synaptic
        sec.write((128 + soullight) & 0xFF);     // signed soullight
        write16(sec, 0);                          // unk4
        return wrapSection(2, sec.toByteArray());
    }

    /** Convenience: build a payload with section 8 (cash) only. */
    public static byte[] cashOnly(Player pl, int cash) {
        PlayerCharacter pc = pl.getCharacter();
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        sec.write(0x0a);
        write32(sec, cash);
        // empty genrep list + minimal fields to keep the parser happy
        write16(sec, 0);                       // num_grs
        sec.write(0x04); sec.write(0x04); sec.write(0x04);
        sec.write(0x00); sec.write(0x00); sec.write(0x00); sec.write(0x00);
        write16(sec, pl.getTransactionID());   // transaction id
        sec.write(0x00); sec.write(0x00); sec.write(0x00); sec.write(0x00);
        sec.write(0x00); sec.write(0x00); sec.write(0x00); sec.write(0x00);
        sec.write(pc.getMisc(PlayerCharacter.MISC_CLASS) * 2);
        sec.write(0x00);
        return wrapSection(8, sec.toByteArray());
    }

    /** Concatenate two payload blobs (section 2 followed by section 8 etc.). */
    public static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * Prepend a 72-byte zero filler. For the {@code disc=0x01} (CharInfo)
     * path the multipart dispatcher only fires UI event {@code 0x13ef}
     * with the buffer past offset {@code 0x48} (= 72) when total size is
     * {@code > 0x42} (= 66 bytes). The 72-byte prefix makes our payload
     * land in the {@code 0x13ef} payload, bypassing the guarded
     * {@code 0xa7} event entirely.
     */
    public static byte[] prependCharInfoFiller(byte[] sections) {
        byte[] out = new byte[72 + sections.length];
        // 72 bytes of zero; the CharInfo header is opaque to us, but the
        // dispatcher only checks size > 0x42, not content validity.
        System.arraycopy(sections, 0, out, 72, sections.length);
        return out;
    }

    private static byte[] wrapSection(int id, byte[] body) {
        byte[] out = new byte[3 + body.length];
        out[0] = (byte) id;
        out[1] = (byte) (body.length & 0xFF);
        out[2] = (byte) ((body.length >> 8) & 0xFF);
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    private static void write16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void write32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    @Override
    public DatagramPacket[] getDatagramPackets() {
        int totalSize = payload.length;
        int packets = Math.max(1, (totalSize + CHUNK_BYTES - 1) / CHUNK_BYTES);
        DatagramPacket[] dps = new DatagramPacket[packets];
        for (int i = 0; i < packets; i++) {
            int offset = i * CHUNK_BYTES;
            int size = Math.min(CHUNK_BYTES, totalSize - offset);

            PacketBuilderUDP1303 pb = new PacketBuilderUDP1303(pl);
            pb.write(7);                    // multipart sub-type
            pb.writeShort(i);
            pb.writeShort(packets);
            pb.write(chainKey);             // chain_key — must differ from login's 0x00
            pb.write(0x00);                 // padding
            pb.write(discriminator);        // 0x01 = CharInfo, 0x02 = CharsysInfo
            pb.write(totalSize & 0xFF);
            pb.write((totalSize >> 8) & 0xFF);
            pb.write((totalSize >> 16) & 0xFF);
            pb.write((totalSize >> 24) & 0xFF);

            pb.write(payload, offset, size);
            dps[i] = pb.getDatagramPackets()[0];
        }
        return dps;
    }
}
