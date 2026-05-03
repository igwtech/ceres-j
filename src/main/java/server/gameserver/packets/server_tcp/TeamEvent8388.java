package server.gameserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

/**
 * TCP {@code 0x83 0x88} — team / group event broadcast.
 *
 * <p>The server pushes this to a member of a team whenever the
 * team composition changes (member joined, left, promoted to
 * leader, etc.). PARTY_A and PARTY_B captures together carry 24 of
 * the 25 in-corpus observations — both characters were partying
 * during those traces, so each of them received {@code 0x8388}
 * pushes from the other's actions.
 *
 * <h3>Wire format (post-FE-frame header)</h3>
 *
 * <pre>
 *   83 88                                            (opcode)
 *   [target_uid LE32]                                (recipient)
 *   [event_type LE32]                                (0x41 / 0x42 / 0x43 …)
 *   [payload_size LE32]                              (size of variable
 *                                                     payload that
 *                                                     follows)
 *   [payload]                                        (payload_size bytes)
 * </pre>
 *
 * <h3>Event-type catalog (from retail evidence)</h3>
 *
 * <table>
 *   <tr><th>Event</th><th>Payload size</th><th>Payload shape</th><th>Likely meaning</th></tr>
 *   <tr><td>{@code 0x41}</td><td>4</td><td>{@code [uid LE32]}</td>
 *       <td>self-uid push (probable leader-set)</td></tr>
 *   <tr><td>{@code 0x42}</td><td>4</td><td>{@code [uid LE32]}</td>
 *       <td>self-uid push (probable disband / leave)</td></tr>
 *   <tr><td>{@code 0x43}</td><td>9</td><td>{@code [uid LE32][role 1B][uid LE32]}</td>
 *       <td>member-list update (member joined with role)</td></tr>
 * </table>
 *
 * <p>Semantic interpretations are unverified — they're inferred
 * from the surrounding capture markers (PARTY join / leave /
 * leader-change). The byte layout is verified.
 */
public final class TeamEvent8388 extends PacketBuilderTCP {

    /** Self-uid event 0x41 — usually fired in pair with 0x43 when a
     *  team is reconfigured. Carries 4-byte payload = recipient UID. */
    public static final int EVENT_TYPE_41 = 0x41;
    /** Self-uid event 0x42 — fired on team disband or member-leave.
     *  Carries 4-byte payload = recipient UID. */
    public static final int EVENT_TYPE_42 = 0x42;
    /** Member-list update 0x43 — fired when a member joins. Payload
     *  is a 9-byte (recipient UID, role byte, member UID) tuple. */
    public static final int EVENT_TYPE_43 = 0x43;

    /**
     * Generic constructor. Most call sites should prefer the static
     * factories {@link #selfEvent} / {@link #memberAddEvent}.
     *
     * @param targetUid recipient's character UID
     * @param eventType {@link #EVENT_TYPE_41} / {@link #EVENT_TYPE_42}
     *                  / {@link #EVENT_TYPE_43} or future variant
     * @param payload   variable-length event payload (may be empty
     *                  but not null)
     */
    public TeamEvent8388(int targetUid, int eventType, byte[] payload) {
        super();
        if (payload == null) payload = new byte[0];
        write(0x83);
        write(0x88);
        writeIntLE(targetUid);
        writeIntLE(eventType);
        writeIntLE(payload.length);
        write(payload);
    }

    /**
     * Build a 0x41 / 0x42-shaped event whose 4-byte payload is the
     * recipient's UID. Mirrors retail samples #1 and #2.
     */
    public static TeamEvent8388 selfEvent(int targetUid, int eventType) {
        byte[] payload = leBytes(targetUid);
        return new TeamEvent8388(targetUid, eventType, payload);
    }

    /**
     * Build a 0x43-shaped member-add event. Payload is the
     * 9-byte sequence {@code [target_uid][role][member_uid]} from
     * retail sample #3.
     */
    public static TeamEvent8388 memberAddEvent(int targetUid,
                                                 int role,
                                                 int memberUid) {
        byte[] payload = new byte[9];
        writeLE32At(payload, 0, targetUid);
        payload[4] = (byte) (role & 0xff);
        writeLE32At(payload, 5, memberUid);
        return new TeamEvent8388(targetUid, EVENT_TYPE_43, payload);
    }

    private void writeIntLE(int v) {
        write(v        & 0xff);
        write((v >> 8 ) & 0xff);
        write((v >> 16) & 0xff);
        write((v >> 24) & 0xff);
    }

    private static byte[] leBytes(int v) {
        return new byte[]{
                (byte) (v),
                (byte) (v >> 8),
                (byte) (v >> 16),
                (byte) (v >> 24)};
    }

    private static void writeLE32At(byte[] dst, int off, int v) {
        dst[off]     = (byte) (v);
        dst[off + 1] = (byte) (v >> 8);
        dst[off + 2] = (byte) (v >> 16);
        dst[off + 3] = (byte) (v >> 24);
    }
}
