package server.gameserver.team;

import java.util.Arrays;

/**
 * Parser for the inner body of {@code TCP S->C 0x83 0x88} (team
 * event broadcast). Used for round-trip tests against retail
 * fixtures and for any future replay tooling that needs to decode
 * captured server traffic.
 *
 * <p>The parser operates on the bytes <strong>after</strong> the
 * FE-frame header — i.e. starting with the {@code 83 88} opcode.
 * Use {@link #stripFrame(byte[])} to drop the 3-byte
 * {@code 0xFE [size LE16]} prefix from a packet built with
 * {@link server.networktools.PacketBuilderTCP}.
 */
public final class TeamEventDecoder {

    /** Decoded team event. {@code payload} is verbatim from the wire. */
    public static final class TeamEvent {
        public final int    targetUid;
        public final int    eventType;
        public final byte[] payload;
        TeamEvent(int t, int e, byte[] p) {
            this.targetUid = t;
            this.eventType = e;
            this.payload = p;
        }

        /** Parse the {@code [uid LE32]} self-payload of 0x41/0x42 events. */
        public Integer asSelfUid() {
            if (payload == null || payload.length != 4) return null;
            return leInt(payload, 0);
        }

        /** Parse the {@code [uid LE32][role][uid LE32]} member-add
         *  payload of 0x43. Returns null if the layout doesn't match. */
        public MemberAddPayload asMemberAdd() {
            if (payload == null || payload.length != 9) return null;
            return new MemberAddPayload(
                    leInt(payload, 0),
                    payload[4] & 0xff,
                    leInt(payload, 5));
        }
    }

    public static final class MemberAddPayload {
        public final int targetUid;
        public final int role;
        public final int memberUid;
        MemberAddPayload(int t, int r, int m) {
            this.targetUid = t;
            this.role = r;
            this.memberUid = m;
        }
    }

    private TeamEventDecoder() {}

    /** Decode a body starting with {@code 83 88}. Returns null if
     *  the body is too short or the opcode bytes don't match. */
    public static TeamEvent decode(byte[] body) {
        if (body == null || body.length < 14) return null;
        if ((body[0] & 0xff) != 0x83 || (body[1] & 0xff) != 0x88) {
            return null;
        }
        int target = leInt(body, 2);
        int event  = leInt(body, 6);
        int size   = leInt(body, 10);
        if (size < 0 || 14 + size > body.length) return null;
        byte[] payload = Arrays.copyOfRange(body, 14, 14 + size);
        return new TeamEvent(target, event, payload);
    }

    /** Strip the 3-byte FE-frame header that {@link
     *  server.networktools.PacketBuilderTCP} prepends. The slice
     *  returned starts with the {@code 83 88} opcode and is sized
     *  according to the LE16 length field stored at offset 1-2. */
    public static byte[] stripFrame(byte[] packet) {
        if (packet == null || packet.length < 3) return null;
        if ((packet[0] & 0xff) != 0xfe) return null;
        int dataSize = (packet[1] & 0xff) | ((packet[2] & 0xff) << 8);
        if (dataSize < 0 || 3 + dataSize > packet.length) return null;
        return Arrays.copyOfRange(packet, 3, 3 + dataSize);
    }

    private static int leInt(byte[] a, int off) {
        return  (a[off]     & 0xff)
             | ((a[off + 1] & 0xff) << 8)
             | ((a[off + 2] & 0xff) << 16)
             | ((a[off + 3] & 0xff) << 24);
    }
}
