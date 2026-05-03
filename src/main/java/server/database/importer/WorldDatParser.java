package server.database.importer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Parses NC2 client world data files ({@code worlds\**\*.dat} and
 * {@code worlds\**\*.bsp}). Both share an identical container format;
 * BSP files carry the engine geometry while DAT files carry the
 * gameplay-relevant element streams (objects, doors, NPCs, waypoints,
 * triggers).
 *
 * <h3>Outer container (16 bytes)</h3>
 *
 * <pre>
 *   off 0  : 12-byte fixed magic 0a f7 3d 88 84 75 84 93 bd ef fd ab
 *   off 12 : LE32 uncompressed size
 *   off 16 : zlib stream → produces the inner file
 * </pre>
 *
 * <h3>Inner stream (TinNS-compatible NC1/NC2 format)</h3>
 *
 * <pre>
 *   PWorldFileHeader  size=0x08 sig=0x000fcfcf section=0x01
 *   { Section header  size=0x0c sig=0x0000ffcf section=N dataSize=L
 *     { Element       size=0x0c sig=0x0ffefef1 type=T dataSize=D
 *       payload (D bytes)
 *     } *
 *   } *
 *   Section header  size=0x0c sig=0x0000ffcf section=0  (terminator)
 * </pre>
 *
 * <p>Element type catalog (verified against a 774-file NC2 sweep):
 * <ul>
 *   <li>{@code 1000002} — passive blob (purpose TBD)</li>
 *   <li>{@code 1000003} — furniture / static object (decoded)</li>
 *   <li>{@code 1000005} — door / portal (decoded)</li>
 *   <li>{@code 1000006} — NPC spawn with optional waypoints (decoded)</li>
 *   <li>others — captured as raw blobs for future RE</li>
 * </ul>
 *
 * <p>Layouts mirror TinNS's {@code WorldDatStruct.hxx}; padding
 * matches GCC default 4-byte alignment, verified by element
 * {@code dataSize == 76} for type 1000003 (52 + optional 24).
 */
public final class WorldDatParser {

    /** 12-byte fixed magic at the head of every wrapped file. */
    public static final byte[] MAGIC = {
            0x0a, (byte) 0xf7, 0x3d, (byte) 0x88,
            (byte) 0x84, 0x75, (byte) 0x84, (byte) 0x93,
            (byte) 0xbd, (byte) 0xef, (byte) 0xfd, (byte) 0xab};

    /** Inner file-header signature. */
    public static final int FILE_HEADER_SIG    = 0x000fcfcf;
    /** Inner section-header signature. */
    public static final int SECTION_HEADER_SIG = 0x0000ffcf;
    /** Inner element-header signature. */
    public static final int ELEMENT_HEADER_SIG = 0x0ffefef1;

    public static final int TYPE_PASSIVE  = 1000002;
    public static final int TYPE_OBJECT   = 1000003;
    public static final int TYPE_DOOR     = 1000005;
    public static final int TYPE_NPC      = 1000006;

    /** Three element types share an identical 20-byte structure:
     *  3 LE32 floats (Y/Z/X) + 8 bytes of type-specific trailer.
     *  They are distinct semantic categories (navigation, scenery
     *  attach points, subway markers — exact meanings unverified)
     *  but share enough structure to share a parser and table. */
    public static final int TYPE_POS_MARKER_9  = 1000009;
    public static final int TYPE_POS_MARKER_10 = 1000010;
    public static final int TYPE_POS_MARKER_14 = 1000014;
    /** All position-marker variants are exactly this many bytes. */
    private static final int POS_MARKER_SIZE = 20;

    /** PSec2ElemType3a — mandatory portion. */
    private static final int OBJECT_HEADER_SIZE = 52;
    /** PSec2ElemType3b — optional bbox portion. */
    private static final int OBJECT_BBOX_SIZE   = 24;
    /** PSec2ElemType5Start — door header. */
    private static final int DOOR_HEADER_SIZE   = 24;
    /** PSec2NPC_EntryPart1. */
    private static final int NPC_HEADER_SIZE    = 32;
    /** PSec2NPC_EntryPart2 — single waypoint coord triple. */
    private static final int NPC_WAYPOINT_SIZE  = 12;

    private WorldDatParser() {}

    public static final class ObjectEntry {
        public int objectId;
        public int worldmodelId;
        public int modelId;
        public float posX, posY, posZ;
        public float rotX, rotY, rotZ;
        public float scale;
        public boolean hasBbox;
        public float bboxLowerX, bboxLowerY, bboxLowerZ;
        public float bboxUpperX, bboxUpperY, bboxUpperZ;
    }

    /**
     * Type 1000002 — passive scenery / non-interactive props.
     *
     * <p>The 76-byte payload has the same physical size as a
     * {@link ObjectEntry} with a bounding box, and the first 12
     * bytes parse cleanly as Y/Z/X position floats across every
     * element observed in the corpus (3946 entries across 774
     * files). The remaining 64 bytes share structural similarities
     * with {@link ObjectEntry} — same default rotations of zero,
     * same uint32-ID pattern at offset 40-43 — but the field
     * <em>meanings</em> differ: scale=20.0 and rotX=1.0 in passive
     * samples don't fit a typical "static furniture" interpretation.
     *
     * <p>v1 surfaces only the high-confidence fields (position,
     * the entry id at offset 40, and the worldmodel-shaped uint16
     * pair at offset 44) and preserves the full 76-byte payload as
     * {@code raw} so future RE passes can refine without re-reading
     * the file system. The other fields are nullable for now.
     */
    public static final class PassiveEntry {
        public float posY, posZ, posX;
        /** uint32 at offset 40 — the same slot that {@link ObjectEntry}
         *  carries {@code mUnknown4} (or {@code worldmodelID}'s u32
         *  alias). Most-likely-but-unverified template ID. */
        public int entryId;
        /** uint16 at offset 44, the worldmodel slot in the 1000003
         *  layout. Value range fits but semantics unverified. */
        public int worldmodelId;
        /** Verbatim 76-byte payload — keep around so refined decoding
         *  can run later without re-walking the source files. */
        public byte[] raw;
    }

    public static final class DoorEntry {
        public int doorId;
        public int worldmodelId;
        public float posX, posY, posZ;
        public String actorType;
        public String params;
    }

    public static final class NpcWaypoint {
        public float posX, posY, posZ;
    }

    public static final class NpcEntry {
        public int npcId;
        public int npcTypeId;
        public int tradeId;
        public float posX, posY, posZ;
        public String actorName;
        public String angle;
        public List<NpcWaypoint> waypoints = new ArrayList<>();
    }

    /**
     * Type 1000009 / 1000010 / 1000014 — 20-byte position marker.
     *
     * <p>Bytes 0-11: position floats (Y/Z/X). Bytes 12-19: 8-byte
     * opaque trailer whose semantics depend on {@link #elementType}:
     * <ul>
     *   <li>{@code 1000010}: trailer is mostly zero (3112 of 34224
     *       entries) — looks like a navigation waypoint or
     *       lightmap-probe marker. Non-zero entries carry
     *       {@code 0x00000001} as a flag at offset 12.</li>
     *   <li>{@code 1000009}: trailer carries a per-marker counter
     *       at offset 12 (e.g. {@code 0x28f30000}, {@code 0x28f30001},
     *       {@code 0x28f30002}) and a small uint32 at offset 16.</li>
     *   <li>{@code 1000014}: trailer mostly fixed
     *       ({@code 0x43480000}) with a per-marker uint32 at
     *       offset 16 (zone-region encoding inferred from
     *       {@code pak_subway.dat}'s clustered values).</li>
     * </ul>
     */
    public static final class PositionMarker {
        public int   elementType;
        public float posY, posZ, posX;
        /** Verbatim 8-byte trailer (offsets 12-19). */
        public byte[] trailer;
    }

    public static final class RawBlob {
        public int sectionId;
        public int elementType;
        public byte[] data;
    }

    public static final class ParsedWorld {
        public final List<ObjectEntry> objects = new ArrayList<>();
        public final List<DoorEntry>   doors   = new ArrayList<>();
        public final List<NpcEntry>    npcs    = new ArrayList<>();
        public final List<PassiveEntry> passives = new ArrayList<>();
        public final List<PositionMarker> markers = new ArrayList<>();
        public final List<RawBlob>     rawBlobs = new ArrayList<>();
        /** Section IDs encountered, in order. */
        public final List<Integer>     sectionIds = new ArrayList<>();
        /** Total elements seen across all sections. */
        public int totalElements;
    }

    /**
     * Parse a wrapped DAT/BSP file from raw bytes (full file contents).
     *
     * @throws ParseException on malformed input
     */
    public static ParsedWorld parse(byte[] raw) throws ParseException {
        if (raw == null || raw.length < 16) {
            throw new ParseException("file too short: " + (raw == null ? -1 : raw.length));
        }
        if (!Arrays.equals(Arrays.copyOf(raw, 12), MAGIC)) {
            throw new ParseException("magic mismatch");
        }
        int declared = leInt(raw, 12);

        byte[] inflated;
        try {
            inflated = inflate(raw, 16, raw.length - 16, declared);
        } catch (DataFormatException e) {
            throw new ParseException("zlib inflate failed: " + e.getMessage());
        }
        if (inflated.length != declared) {
            // Some files report a size that matches but defensive check
            // protects against silently truncated content.
            throw new ParseException(
                    "uncompressed size mismatch: " + inflated.length
                    + " vs declared " + declared);
        }
        return parseInflated(inflated);
    }

    /** Walk the inflated TinNS-format byte stream. */
    public static ParsedWorld parseInflated(byte[] in) throws ParseException {
        if (in.length < 12) throw new ParseException("inner too short");
        int hsz = leInt(in, 0);
        int hsig = leInt(in, 4);
        int hsec = leInt(in, 8);
        if (hsz != 0x08 || hsig != FILE_HEADER_SIG || hsec != 0x01) {
            throw new ParseException(String.format(
                    "bad file header: size=0x%x sig=0x%x section=0x%x",
                    hsz, hsig, hsec));
        }
        int off = hsz + 4; // 12

        ParsedWorld out = new ParsedWorld();
        while (off + 16 <= in.length) {
            int sz   = leInt(in, off);
            int sig  = leInt(in, off + 4);
            int sec  = leInt(in, off + 8);
            int dsz  = leInt(in, off + 12);
            if (sig != SECTION_HEADER_SIG) {
                throw new ParseException(String.format(
                        "bad section sig 0x%x at off 0x%x", sig, off));
            }
            if (sec == 0) break; // terminator
            out.sectionIds.add(sec);
            int elemOff  = off + sz + 4; // skip header (16 bytes)
            int sectionEnd = elemOff + dsz;
            if (sectionEnd > in.length) {
                throw new ParseException("section overflows file");
            }
            // Section 2 has element-tagged sub-records; other sections
            // we currently capture as raw blobs for later analysis.
            if (sec == 2) {
                while (elemOff + 16 <= sectionEnd) {
                    int esz   = leInt(in, elemOff);
                    int esig  = leInt(in, elemOff + 4);
                    int etype = leInt(in, elemOff + 8);
                    int edsz  = leInt(in, elemOff + 12);
                    if (esig != ELEMENT_HEADER_SIG) {
                        throw new ParseException(String.format(
                                "bad element sig 0x%x at off 0x%x",
                                esig, elemOff));
                    }
                    int dataOff = elemOff + esz + 4;
                    if (dataOff + edsz > sectionEnd) {
                        throw new ParseException("element overflows section");
                    }
                    decodeElement(in, dataOff, edsz, sec, etype, out);
                    out.totalElements++;
                    elemOff = dataOff + edsz;
                }
            } else {
                // Whole section captured as one blob.
                RawBlob blob = new RawBlob();
                blob.sectionId = sec;
                blob.elementType = -1;
                blob.data = Arrays.copyOfRange(in, elemOff, sectionEnd);
                out.rawBlobs.add(blob);
            }
            off = sectionEnd;
        }
        return out;
    }

    private static void decodeElement(byte[] in, int off, int size,
                                       int sectionId, int elementType,
                                       ParsedWorld out)
            throws ParseException {
        ByteBuffer bb = ByteBuffer.wrap(in, off, size).order(ByteOrder.LITTLE_ENDIAN);
        switch (elementType) {
        case TYPE_PASSIVE:
            decodePassive(in, off, size, out);
            return;
        case TYPE_POS_MARKER_9:
        case TYPE_POS_MARKER_10:
        case TYPE_POS_MARKER_14:
            decodePosMarker(in, off, size, elementType, out);
            return;
        case TYPE_OBJECT:
            decodeObject(bb, size, out);
            return;
        case TYPE_DOOR:
            decodeDoor(bb, size, out);
            return;
        case TYPE_NPC:
            decodeNpc(bb, size, out);
            return;
        default:
            // Unknown element type — capture the raw payload so a
            // future RE pass can analyse it without re-reading the
            // file.
            RawBlob blob = new RawBlob();
            blob.sectionId = sectionId;
            blob.elementType = elementType;
            blob.data = Arrays.copyOfRange(in, off, off + size);
            out.rawBlobs.add(blob);
        }
    }

    private static void decodePosMarker(byte[] in, int off, int size,
                                         int elementType, ParsedWorld out)
            throws ParseException {
        if (size != POS_MARKER_SIZE) {
            throw new ParseException("pos-marker size " + size
                    + " expected " + POS_MARKER_SIZE
                    + " (type=" + elementType + ")");
        }
        PositionMarker m = new PositionMarker();
        m.elementType = elementType;
        m.posY = Float.intBitsToFloat(leInt(in, off));
        m.posZ = Float.intBitsToFloat(leInt(in, off + 4));
        m.posX = Float.intBitsToFloat(leInt(in, off + 8));
        m.trailer = Arrays.copyOfRange(in, off + 12, off + 20);
        out.markers.add(m);
    }

    private static void decodePassive(byte[] in, int off, int size,
                                       ParsedWorld out)
            throws ParseException {
        // Empirically every observed type-1000002 element is exactly
        // 76 bytes — same physical size as an OBJECT_HEADER + bbox.
        if (size != OBJECT_HEADER_SIZE + OBJECT_BBOX_SIZE) {
            throw new ParseException("passive size " + size
                    + " expected " + (OBJECT_HEADER_SIZE + OBJECT_BBOX_SIZE));
        }
        PassiveEntry p = new PassiveEntry();
        p.posY        = Float.intBitsToFloat(leInt(in, off));
        p.posZ        = Float.intBitsToFloat(leInt(in, off + 4));
        p.posX        = Float.intBitsToFloat(leInt(in, off + 8));
        p.entryId     = leInt(in, off + 40);
        p.worldmodelId = (in[off + 44] & 0xff)
                       | ((in[off + 45] & 0xff) << 8);
        p.raw         = Arrays.copyOfRange(in, off, off + size);
        out.passives.add(p);
    }

    private static void decodeObject(ByteBuffer bb, int size, ParsedWorld out)
            throws ParseException {
        if (size != OBJECT_HEADER_SIZE
                && size != OBJECT_HEADER_SIZE + OBJECT_BBOX_SIZE) {
            throw new ParseException("object size " + size
                    + " not in {" + OBJECT_HEADER_SIZE
                    + "," + (OBJECT_HEADER_SIZE + OBJECT_BBOX_SIZE) + "}");
        }
        ObjectEntry o = new ObjectEntry();
        o.posY  = bb.getFloat();
        o.posZ  = bb.getFloat();
        o.posX  = bb.getFloat();
        o.rotY  = bb.getFloat();
        o.rotZ  = bb.getFloat();
        o.rotX  = bb.getFloat();
        o.scale = bb.getFloat();
        bb.getInt();                              // mUnknown2
        o.modelId = bb.getShort() & 0xffff;
        bb.getShort();                            // 2-byte alignment pad
        bb.getInt();                              // mUnknown3
        bb.getInt();                              // mUnknown4
        o.worldmodelId = bb.getShort() & 0xffff;
        bb.getShort();                            // mUnknown5
        o.objectId = bb.getInt();

        if (size == OBJECT_HEADER_SIZE + OBJECT_BBOX_SIZE) {
            o.hasBbox = true;
            o.bboxLowerY = bb.getFloat();
            o.bboxLowerZ = bb.getFloat();
            o.bboxLowerX = bb.getFloat();
            o.bboxUpperY = bb.getFloat();
            o.bboxUpperZ = bb.getFloat();
            o.bboxUpperX = bb.getFloat();
        }
        out.objects.add(o);
    }

    private static void decodeDoor(ByteBuffer bb, int size, ParsedWorld out)
            throws ParseException {
        if (size < DOOR_HEADER_SIZE) {
            throw new ParseException("door size " + size + " < " + DOOR_HEADER_SIZE);
        }
        DoorEntry d = new DoorEntry();
        bb.getShort();                            // mUnknown1
        bb.getShort();                            // mUnknown1bis
        d.posY = bb.getFloat();
        d.posZ = bb.getFloat();
        d.posX = bb.getFloat();
        int actorLen = bb.get() & 0xff;
        int paramLen = bb.get() & 0xff;
        bb.getShort();                            // mUnknown5
        d.doorId       = bb.getShort() & 0xffff;
        d.worldmodelId = bb.getShort() & 0xffff;

        int strBytes = actorLen + paramLen;
        if (DOOR_HEADER_SIZE + strBytes != size) {
            throw new ParseException("door string size mismatch: header "
                    + actorLen + "+" + paramLen + " vs payload "
                    + (size - DOOR_HEADER_SIZE));
        }
        byte[] both = new byte[strBytes];
        bb.get(both);
        d.actorType = nullTerminated(both, 0, actorLen);
        d.params    = nullTerminated(both, actorLen, paramLen);
        out.doors.add(d);
    }

    private static void decodeNpc(ByteBuffer bb, int size, ParsedWorld out)
            throws ParseException {
        if (size < NPC_HEADER_SIZE) {
            throw new ParseException("npc size " + size + " < " + NPC_HEADER_SIZE);
        }
        NpcEntry n = new NpcEntry();
        bb.getInt();                              // mUnknown1 (0x20001200)
        n.posY = bb.getFloat();
        n.posZ = bb.getFloat();
        n.posX = bb.getFloat();
        n.npcTypeId = bb.getInt();
        int actorLen = bb.get() & 0xff;
        int angleLen = bb.get() & 0xff;
        n.npcId      = bb.getShort() & 0xffff;
        int waypointCount = bb.get() & 0xff;
        bb.get();                                 // mUnknown2a
        bb.get();                                 // mUnknown2b
        bb.get();                                 // mUnknown2c
        n.tradeId   = bb.getShort() & 0xffff;
        bb.getShort();                            // mUnknown4

        int expected = NPC_HEADER_SIZE + actorLen + angleLen
                + waypointCount * NPC_WAYPOINT_SIZE;
        if (expected != size) {
            throw new ParseException("npc size mismatch: expected "
                    + expected + " got " + size);
        }
        byte[] actor = new byte[actorLen];
        byte[] angle = new byte[angleLen];
        bb.get(actor);
        bb.get(angle);
        n.actorName = nullTerminated(actor, 0, actorLen);
        n.angle     = nullTerminated(angle, 0, angleLen);

        for (int i = 0; i < waypointCount; i++) {
            NpcWaypoint w = new NpcWaypoint();
            w.posY = bb.getFloat();
            w.posZ = bb.getFloat();
            w.posX = bb.getFloat();
            n.waypoints.add(w);
        }
        out.npcs.add(n);
    }

    private static String nullTerminated(byte[] arr, int off, int len) {
        int end = off + len;
        int term = end;
        for (int i = off; i < end; i++) {
            if (arr[i] == 0) { term = i; break; }
        }
        return new String(arr, off, term - off, StandardCharsets.US_ASCII);
    }

    private static int leInt(byte[] a, int off) {
        return  (a[off]     & 0xff)
             | ((a[off + 1] & 0xff) << 8)
             | ((a[off + 2] & 0xff) << 16)
             | ((a[off + 3] & 0xff) << 24);
    }

    private static byte[] inflate(byte[] src, int off, int len, int hint)
            throws DataFormatException {
        Inflater inf = new Inflater();
        inf.setInput(src, off, len);
        // Pre-size the output buffer to the declared size so most
        // files inflate in a single call.
        ByteArrayOutputStream baos = new ByteArrayOutputStream(
                Math.max(hint, 1024));
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) {
                        throw new DataFormatException(
                                "inflater stalled (needsInput="
                                + inf.needsInput() + ")");
                    }
                    break;
                }
                baos.write(buf, 0, n);
            }
        } finally {
            inf.end();
        }
        return baos.toByteArray();
    }

    /** Dedicated checked exception so callers can distinguish parse
     *  errors from generic IOExceptions. */
    public static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;
        public ParseException(String msg) { super(msg); }
    }
}
