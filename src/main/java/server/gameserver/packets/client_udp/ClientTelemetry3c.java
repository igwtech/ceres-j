package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

/**
 * Raw (unreliable) C&rarr;S client telemetry on outer sub-type
 * {@code 0x3c} &mdash; the client-side counterpart to the server's
 * {@link server.gameserver.packets.server_udp.AttributeUpdate3c}
 * attribute-update channel.
 *
 * <h3>Wire format (retail-verified)</h3>
 *
 * <p>Byte-pinned from
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}
 * (live server 157.90.195.74). Fixed 12-byte raw sub-packet inside
 * the {@code 0x13} wrapper:
 *
 * <pre>
 *   [0]      0x3c                  raw sub-opcode
 *   [1]      seq u8                low counter (0x01 / 0x03 observed)
 *   [2]      0x00                  CONSTANT
 *   [3]      tag u8                channel tag (0x00..0x09 observed)
 *   [4..7]   value  i32 LE         signed; e.g. 0xfffffffe (-2),
 *                                  0xffffffff (-1), small positives
 *   [8..11]  measure f32 LE        monotone-ish float (looks like a
 *                                  client uptime / tick measure)
 * </pre>
 *
 * <p>All 13 C&rarr;S observations were exactly this 12-byte form
 * (length, byte[2]=0x00 invariant; byte[1] &isin; {0x01,0x03}). It
 * recurs at a ~3&nbsp;s cadence during play (median inter-arrival
 * &asymp;3&nbsp;s once active). It mirrors the S&rarr;C
 * {@code 3c 01 00 [tag] [i32] [i32]} {@code AttributeUpdate3c}
 * layout (same {@code 3c .. 00 tag} prefix, 12 B).
 *
 * <h3>Server behaviour</h3>
 *
 * <p>Fire-and-forget client telemetry: retail's server does
 * <em>not</em> reply to the C&rarr;S {@code 0x3c} (the S&rarr;C
 * {@code 0x3c} frames in the capture are server-initiated attribute
 * pushes, never request-paired to a C&rarr;S poll). There is no
 * server-side action to take here. Recognising it cleanly stops the
 * recurring {@code Unknown UDP13 Packet} log noise; the decoded
 * fields are exposed for future use (e.g. anti-cheat / client-state
 * sanity) without committing to a semantic that the single capture
 * cannot pin.
 */
public class ClientTelemetry3c extends GamePacketDecoderUDP {

    private final int seq;
    private final int tag;
    private final int value;
    private final float measure;

    public ClientTelemetry3c(byte[] subPacket) {
        super(subPacket);
        int s = -1, t = -1, v = 0;
        float m = 0f;
        if (subPacket.length >= 12) {
            s = subPacket[1] & 0xFF;
            t = subPacket[3] & 0xFF;
            v = (subPacket[4] & 0xFF)
              | ((subPacket[5] & 0xFF) << 8)
              | ((subPacket[6] & 0xFF) << 16)
              | ((subPacket[7] & 0xFF) << 24);
            int fb = (subPacket[8] & 0xFF)
                   | ((subPacket[9] & 0xFF) << 8)
                   | ((subPacket[10] & 0xFF) << 16)
                   | ((subPacket[11] & 0xFF) << 24);
            m = Float.intBitsToFloat(fb);
        }
        this.seq = s;
        this.tag = t;
        this.value = v;
        this.measure = m;
    }

    /** Low counter byte (body[1]); {@code -1} if body too short. */
    public int getSeq() {
        return seq;
    }

    /** Channel tag (body[3]); {@code -1} if body too short. */
    public int getTag() {
        return tag;
    }

    /** Signed 32-bit value (body[4..7] LE). */
    public int getValue() {
        return value;
    }

    /** 32-bit float measure (body[8..11] LE). */
    public float getMeasure() {
        return measure;
    }

    public void execute(Player pl) {
        // Fire-and-forget telemetry — recognise only, no reply.
    }
}
