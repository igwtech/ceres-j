package server.gameserver.npc;

/**
 * Five-value enum describing the high-level state byte of a mob/NPC
 * carried in the {@code UDP S->C 0x03/0x2d} 54-byte broadcast (offset
 * 4 inside the inner payload).
 *
 * <p>Population frequencies measured across the full retail capture
 * corpus (64,757 long-variant broadcasts):
 *
 * <pre>
 *   IDLE       0x75   36 416  (56 %)
 *   COMBAT     0x71   26 466  (41 %)
 *   TRANSITION 0x70    1 839  ( 3 %)
 *   RARE_72    0x72       29
 *   RARE_6F    0x6f        7
 * </pre>
 *
 * <p>The two rare states {@code RARE_72} and {@code RARE_6F} are
 * preserved here so the parser can round-trip every observed value
 * without falling back to a generic "unknown" bucket. Their semantic
 * meaning is not yet known and decoding them is deferred until a
 * capture context tells us the trigger (likely stunned and another
 * transient combat sub-state).
 */
public enum MobState {

    IDLE      (0x75),
    COMBAT    (0x71),
    TRANSITION(0x70),
    RARE_72   (0x72),
    RARE_6F   (0x6f);

    private final int wireByte;

    MobState(int wireByte) { this.wireByte = wireByte; }

    /** The single byte that encodes this state on the wire. */
    public int wireByte() { return wireByte; }

    /** Decode a wire byte into the matching enum, or {@code null}
     *  for any byte we have never observed. The caller decides
     *  whether to treat unknown bytes as {@link #TRANSITION} or to
     *  log + drop. */
    public static MobState fromWire(int b) {
        switch (b & 0xff) {
        case 0x75: return IDLE;
        case 0x71: return COMBAT;
        case 0x70: return TRANSITION;
        case 0x72: return RARE_72;
        case 0x6f: return RARE_6F;
        default:   return null;
        }
    }
}
