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
 * <p>Cross-correlated with the PARTY_B marker file
 * ({@code strace/nc2_strace_RETAIL_RETAIL_LONG_PARTY_B_20260503_130343.markers}):
 *
 * <table>
 *   <tr><th>Event</th><th>Payload size</th><th>Payload shape</th><th>Closest capture marker (Δt)</th><th>Likely meaning</th></tr>
 *   <tr><td>{@code 0x41}</td><td>4</td><td>{@code [uid LE32]}</td>
 *       <td>INVITE_TEAM (-0.9s)</td>
 *       <td>self-uid push — invite issued / leader-set</td></tr>
 *   <tr><td>{@code 0x42}</td><td>4</td><td>{@code [uid LE32]}</td>
 *       <td>ACCEPT_INVITE_TEAM (-3.1s)</td>
 *       <td>self-uid push — invite acked phase 1</td></tr>
 *   <tr><td>{@code 0x43}</td><td>9</td><td>{@code [uid LE32][role 1B][uid LE32]}</td>
 *       <td>ACCEPT_INVITE_TEAM (-2.9s)</td>
 *       <td>member-list update — member joined with role</td></tr>
 *   <tr><td>{@code 0x44}</td><td>4</td><td>{@code [uid LE32]}</td>
 *       <td>REINVITE_TEAM (+5.7s)</td>
 *       <td>self-uid push — invite cleared / member removed</td></tr>
 *   <tr><td>{@code 0x48}</td><td>4</td><td>{@code [uid LE32]}</td>
 *       <td>LEAVE_TEAM_AGAIN (-3.5s, fires twice)</td>
 *       <td>self-uid push — team-disband / leave broadcast</td></tr>
 * </table>
 *
 * <p>Semantic interpretations are unverified — they're inferred
 * from the surrounding capture markers (PARTY join / leave /
 * leader-change). The byte layout is verified against every
 * sample in the corpus (16 unique by content, 25 raw observations).
 */
public final class TeamEvent8388 extends PacketBuilderTCP {

    /** Self-uid event 0x41 — invite issued / leader-set. Fired
     *  immediately before the joining member receives 0x42 + 0x43.
     *  Carries 4-byte payload = recipient UID. */
    public static final int EVENT_TYPE_41 = 0x41;
    /** Self-uid event 0x42 — invite acked phase 1. Fired in pair
     *  with the 0x43 member-add for the joining member.
     *  Carries 4-byte payload = recipient UID. */
    public static final int EVENT_TYPE_42 = 0x42;
    /** Member-list update 0x43 — fired when a member joins.
     *  Payload is a 9-byte (recipient UID, role byte, member UID)
     *  tuple. */
    public static final int EVENT_TYPE_43 = 0x43;
    /** Self-uid event 0x44 — invite cleared / member removed,
     *  fired around the REINVITE / LEAVE marker burst. Carries
     *  4-byte payload = recipient UID. */
    public static final int EVENT_TYPE_44 = 0x44;
    /** Self-uid event 0x48 — team-disband / leave broadcast.
     *  Fires twice near LEAVE_TEAM_AGAIN — once per remaining
     *  team member. Carries 4-byte payload = recipient UID. */
    public static final int EVENT_TYPE_48 = 0x48;

    /**
     * Generic constructor. Most call sites should prefer the static
     * factories {@link #selfEvent} / {@link #memberAddEvent}.
     *
     * @param targetUid recipient's character UID
     * @param eventType {@link #EVENT_TYPE_41} / {@link #EVENT_TYPE_42}
     *                  / {@link #EVENT_TYPE_43} / {@link #EVENT_TYPE_44}
     *                  / {@link #EVENT_TYPE_48} or future variant
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
