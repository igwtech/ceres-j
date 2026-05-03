package server.gameserver.npc;

/**
 * Parser for the inner body of {@code UDP C->S 0x03/0x2d} (Reliable /
 * NPCData) sent by the client when piloting a drone.
 *
 * <p>Two variants observed:
 *
 * <h3>5-byte status / heartbeat</h3>
 * <pre>
 *   off 0-3 : drone_id LE32 (often zero on initial frame)
 *   off 4   : status byte (0x0b — purpose TBD; appears once per
 *             drone session at start-up)
 * </pre>
 *
 * <h3>41-byte control frame</h3>
 *
 * <p>Total bytes = 41 = 4 (wrapper) + 37 inner. After stripping the
 * {@code 03 [seq2] 2d} wrapper:
 *
 * <pre>
 *   off 0-3 : drone_id        LE32
 *   off 4   : drone class     0x02
 *   off 5-8 : pos_x           LE32 float
 *   off 9-12: pos_y           LE32 float
 *   off 13-16: pos_z          LE32 float
 *   off 17-36: trailer        20 bytes (orientation / fire flags / etc.)
 * </pre>
 *
 * <p>Verified against
 * {@code RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715} markers
 * {@code DRONE_INUSE} / {@code DRONE_INUSE_FIRING} where successive
 * frames at ~10 Hz show monotonically-changing Y coordinates (drone
 * sliding sideways) while X and Z are constant — exactly the
 * behaviour expected from a stationary pilot pitching a drone.
 */
public final class DroneControlDecoder {

    public static final int CONTROL_LEN   = 37;   // 41 - 4 wrapper
    public static final int HEARTBEAT_LEN = 5;
    /** Class byte at offset 4 distinguishing a drone from other
     *  C->S 0x03/0x2d entities. */
    public static final int CLASS_DRONE   = 0x02;

    public static final class DroneControl {
        public final int   droneId;
        public final int   classByte;
        public final float posX, posY, posZ;
        /** Bytes 17..36 inclusive (20 bytes opaque). */
        public final byte[] tail;
        DroneControl(int droneId, int classByte,
                     float x, float y, float z, byte[] tail) {
            this.droneId   = droneId;
            this.classByte = classByte;
            this.posX = x;
            this.posY = y;
            this.posZ = z;
            this.tail = tail;
        }
    }

    public static final class DroneHeartbeat {
        public final int droneId;
        public final int statusByte;
        DroneHeartbeat(int droneId, int statusByte) {
            this.droneId   = droneId;
            this.statusByte = statusByte;
        }
    }

    private DroneControlDecoder() {}

    /** Decode the 37-byte control frame. Returns null if the inner
     *  body is the wrong length or the class byte is not 0x02 (which
     *  would indicate a different C->S 0x03/0x2d entity type that we
     *  haven't decoded yet). */
    public static DroneControl decodeControl(byte[] inner) {
        if (inner == null || inner.length != CONTROL_LEN) return null;
        if ((inner[4] & 0xff) != CLASS_DRONE) return null;
        int id = leInt(inner, 0);
        float x = Float.intBitsToFloat(leInt(inner, 5));
        float y = Float.intBitsToFloat(leInt(inner, 9));
        float z = Float.intBitsToFloat(leInt(inner, 13));
        byte[] tail = new byte[CONTROL_LEN - 17];
        System.arraycopy(inner, 17, tail, 0, tail.length);
        return new DroneControl(id, CLASS_DRONE, x, y, z, tail);
    }

    /** Decode the 5-byte heartbeat. */
    public static DroneHeartbeat decodeHeartbeat(byte[] inner) {
        if (inner == null || inner.length != HEARTBEAT_LEN) return null;
        return new DroneHeartbeat(leInt(inner, 0), inner[4] & 0xff);
    }

    private static int leInt(byte[] a, int off) {
        return  (a[off]     & 0xff)
             | ((a[off + 1] & 0xff) << 8)
             | ((a[off + 2] & 0xff) << 16)
             | ((a[off + 3] & 0xff) << 24);
    }
}
