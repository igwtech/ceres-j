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
 * through {@code UDP 0x03/0x1f tag=0x1b} and is handled by the
 * existing {@code LocalChat}/{@code LocalChatMessage} pair.
 */
public final class Chat8317 extends PacketBuilderTCP {

    /** Channel-byte enum. */
    public static final byte CHANNEL_BUDDY   = 0x00;
    public static final byte CHANNEL_CLAN    = 0x02;
    public static final byte CHANNEL_TEAM    = 0x03;
    public static final byte CHANNEL_WHISPER = 0x04;

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
}
