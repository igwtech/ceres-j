package server.gameserver.npc;

/**
 * Parser for the inner body of {@code UDP S->C 0x03/0x2d}
 * (Reliable / NPCData) — both the 9-byte heartbeat variant and the
 * 54-byte (post-strip) full-state variant.
 *
 * <h3>Bytes seen on the wire (post-stripped — without the
 * {@code 03 [seq2] 2d} wrapper)</h3>
 *
 * <p>Long variant (50 bytes inner; 54 incl. wrapper byte):
 *
 * <pre>
 *   off 0-3  : npc_id          LE32
 *   off 4    : state           {@link MobState} byte
 *   off 5    : flags / sub-class (modifier — varies; preserved as-is)
 *   off 6-9  : position altitude (LE32 float — verified against
 *              combat samples; idle samples sometimes use this slot
 *              for an unrelated value when state byte 5 != 0x20/0x40)
 *   off 10-13: target_id       LE32  (0xFFFFFFFF = no target)
 *   off 14+  : remainder       50 - 14 = 36 bytes whose layout is
 *              still being reverse-engineered. Captured verbatim as
 *              {@link DecodedMob#tail} so producers can replay them.
 * </pre>
 *
 * <p>Short variant (8 bytes inner; 9 incl. wrapper):
 *
 * <pre>
 *   off 0-1  : map_id          LE16
 *   off 2    : 0x00
 *   off 3    : 0x08            (sub-block marker)
 *   off 4-7  : status flags    LE32
 * </pre>
 *
 * <p>This decoder is deliberately conservative: it surfaces only the
 * fields we have ground truth for (npc_id, state, target_id,
 * altitude). The rest of the body is held verbatim so future passes
 * can decode without re-touching the capture corpus.
 */
public final class MobDataDecoder {

    /** Inner body length (post-strip) for the heartbeat variant. */
    public static final int SHORT_LEN = 8;
    /** Inner body length (post-strip) for the full state variant. */
    public static final int LONG_LEN  = 50;

    /** Marker for "no current target" (drone or no aggro). */
    public static final int NO_TARGET = 0xFFFFFFFF;

    public static final class DecodedMob {
        public final int npcId;
        public final MobState state;
        /** Raw state byte if {@link #state} is null (unmapped value). */
        public final int rawStateByte;
        /** Modifier byte at offset 5; meaning is state-dependent. */
        public final int flagsByte;
        public final float altitude;
        public final int targetId;
        /** Bytes 14..49 (inclusive) — opaque trailer. */
        public final byte[] tail;

        DecodedMob(int npcId, MobState state, int rawStateByte,
                   int flagsByte, float altitude, int targetId,
                   byte[] tail) {
            this.npcId = npcId;
            this.state = state;
            this.rawStateByte = rawStateByte;
            this.flagsByte = flagsByte;
            this.altitude = altitude;
            this.targetId = targetId;
            this.tail = tail;
        }

        public boolean isInCombat() { return state == MobState.COMBAT; }
        public boolean hasTarget()  { return targetId != NO_TARGET; }
    }

    public static final class DecodedHeartbeat {
        public final int mapId;
        public final int statusFlags;
        DecodedHeartbeat(int mapId, int statusFlags) {
            this.mapId = mapId;
            this.statusFlags = statusFlags;
        }
    }

    private MobDataDecoder() {}

    /** Decode a long-variant inner body (50 bytes). Returns null on
     *  bad length so the caller can route the heartbeat variant
     *  separately. */
    public static DecodedMob decodeLong(byte[] inner) {
        if (inner == null || inner.length != LONG_LEN) return null;
        int npcId    = leInt(inner, 0);
        int state    = inner[4] & 0xff;
        int flags    = inner[5] & 0xff;
        float alt    = Float.intBitsToFloat(leInt(inner, 6));
        int target   = leInt(inner, 10);
        byte[] tail  = new byte[LONG_LEN - 14];
        System.arraycopy(inner, 14, tail, 0, tail.length);
        return new DecodedMob(npcId, MobState.fromWire(state),
                state, flags, alt, target, tail);
    }

    /** Decode a short-variant heartbeat body (8 bytes). */
    public static DecodedHeartbeat decodeShort(byte[] inner) {
        if (inner == null || inner.length != SHORT_LEN) return null;
        int mapId = (inner[0] & 0xff) | ((inner[1] & 0xff) << 8);
        // bytes [2]=0x00 [3]=0x08 are fixed; not surfaced.
        int flags = leInt(inner, 4);
        return new DecodedHeartbeat(mapId, flags);
    }

    private static int leInt(byte[] a, int off) {
        return  (a[off]     & 0xff)
             | ((a[off + 1] & 0xff) << 8)
             | ((a[off + 2] & 0xff) << 16)
             | ((a[off + 3] & 0xff) << 24);
    }
}
