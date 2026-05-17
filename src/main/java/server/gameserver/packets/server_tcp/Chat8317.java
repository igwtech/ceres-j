package server.gameserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

/**
 * TCP {@code 0x83 0x17} — chat reflection from server to recipient.
 *
 * <p>When a client sends cross-channel chat (whisper, team, clan,
 * buddy) via UDP {@code 0x03/0x1f tag=0x3b}, the server reflects the
 * message to the recipient over TCP using this opcode. This is the
 * <strong>only</strong> game-event traffic on the TCP keepalive
 * channel — chat picked TCP so reliability + ordering come for free.
 *
 * <h3>Wire format</h3>
 *
 * <pre>
 * 83 17 [sender_uid LE4] [name_len 1B] [channel 1B] 00 [sender_name ASCII] [message ASCII]
 * </pre>
 *
 * <p>Both the sender name and the message are <strong>not</strong>
 * null-terminated; their lengths are derived from the LE2 length
 * field at the FE-frame header (and {@code name_len} for the
 * name/message split inside the body).
 *
 * <h3>Channel byte enum (verified against PARTY_B capture)</h3>
 *
 * <table>
 *   <tr><th>Channel</th><th>Meaning</th></tr>
 *   <tr><td>{@code 0x00}</td><td>Buddy chat</td></tr>
 *   <tr><td>{@code 0x02}</td><td>Clan chat</td></tr>
 *   <tr><td>{@code 0x03}</td><td>Team chat</td></tr>
 *   <tr><td>{@code 0x04}</td><td>Direct / whisper</td></tr>
 * </table>
 *
 * <p>Local proximity chat does NOT use this packet — it travels
 * through {@code UDP 0x03/0x1f tag=0x1b}. Server-originated
 * <strong>system / GM-command replies</strong> use this packet with
 * the {@linkplain #system(String, String) system-broadcast} form
 * ({@code sender_uid = 0xffffffff}, {@code channel = 0xff}), exactly
 * as retail emits NCPD bulletins (verified against
 * {@code RETAIL_RETAIL_VEHICLE_DRONE} —
 * {@code 83 17 ff ff ff ff 0c ff 00 "Server Admin" + msg}).
 */
public final class Chat8317 extends PacketBuilderTCP {

    /** Channel-byte enum. */
    public static final byte CHANNEL_BUDDY   = 0x00;
    public static final byte CHANNEL_CLAN    = 0x02;
    public static final byte CHANNEL_TEAM    = 0x03;
    public static final byte CHANNEL_WHISPER = 0x04;
    /** Local "say" / proximity speech bubble channel. */
    public static final byte CHANNEL_LOCAL   = 0x04;
    /** Server / system broadcast (NCPD bulletin, GM reply). */
    public static final int  CHANNEL_SYSTEM  = 0xff;

    /** Sentinel sender UID retail uses for system/broadcast lines. */
    public static final int  SYSTEM_UID      = 0xffffffff;

    /**
     * Build a reflection packet.
     *
     * @param senderUid 32-bit player UID (LE on the wire)
     * @param senderName ASCII sender display name
     * @param channel one of {@link #CHANNEL_BUDDY},
     *        {@link #CHANNEL_CLAN}, {@link #CHANNEL_TEAM},
     *        {@link #CHANNEL_WHISPER}
     * @param message ASCII chat content (already passed any
     *        sanitization the chat manager applies)
     */
    public Chat8317(int senderUid, String senderName, int channel,
                    String message) {
        super();
        if (senderName == null) senderName = "";
        if (message == null)    message = "";
        if (senderName.length() > 0xff) {
            throw new IllegalArgumentException(
                "sender name >255 chars: " + senderName.length());
        }

        byte[] nameBytes = senderName.getBytes();
        byte[] msgBytes  = message.getBytes();

        write(0x83);
        write(0x17);
        // sender_uid LE4
        write(senderUid);
        write(senderUid >> 8);
        write(senderUid >> 16);
        write(senderUid >> 24);
        // name_len + channel + separator
        write(nameBytes.length);
        write(channel & 0xff);
        write(0);
        // name + message (no null terminators; reader uses
        // name_len + outer FE-frame length to split)
        write(nameBytes);
        write(msgBytes);
    }

    /**
     * Build a server / system broadcast line — the format retail uses
     * for NCPD bulletins and the channel the client renders for
     * server-originated text (e.g. GM-command replies).
     *
     * <p>Byte-pinned against {@code RETAIL_RETAIL_VEHICLE_DRONE}:
     * <pre>83 17  ff ff ff ff  [name_len]  ff  00  [name][msg]</pre>
     * (sample: {@code ff ff ff ff 0c ff 00 "Server Admin" ...}).
     *
     * @param senderName display name shown before the message
     *        (retail uses {@code "Server Admin"}); may be empty
     * @param message    system message text
     */
    public static Chat8317 system(String senderName, String message) {
        return new Chat8317(SYSTEM_UID, senderName, CHANNEL_SYSTEM,
                message);
    }
}
