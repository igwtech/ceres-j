package server.gameserver.chat;

import java.nio.charset.StandardCharsets;

/**
 * Parser for {@code UDP C->S 0x03/0x1f tag=0x3b} cross-channel chat
 * bodies.
 *
 * <h3>Wire format</h3>
 *
 * <pre>
 * 02 00 3b [channel 1B] [target_uid LE4] [message ASCII null-terminated]
 * </pre>
 *
 * <p>The leading {@code 02 00} is the {@code 0x03/0x1f} body's
 * standard "header pair" (sequence indicator). The tag byte
 * {@code 0x3b} routes this packet here. The channel byte selects
 * whisper / team / clan / buddy. The {@code target_uid} field is
 * meaningful for whisper; team/clan/buddy ignore it (server
 * substitutes the correct group/clan/buddy roster).
 *
 * <p>Verified against {@code RETAIL_RETAIL_LONG_PARTY_B} samples at
 * markers {@code DIRECT_CHAT}, {@code TEAM_CHAT}, {@code BUDDY_CHAT},
 * {@code CLAN_CHAT}.
 *
 * <p>Phase 2 only ships the decoder; the actual chat fan-out lands
 * in a follow-up commit that wires the {@link ChatBroadcastIntent}
 * handler to {@link server.gameserver.WorldMessageBus}.
 */
public final class Chat3bDecoder {

    /** Channel byte values. */
    public static final int CHANNEL_BUDDY   = 0x00;
    public static final int CHANNEL_CLAN    = 0x02;
    public static final int CHANNEL_TEAM    = 0x03;
    public static final int CHANNEL_WHISPER = 0x04;

    /** Decoded chat-message intent. {@code targetUid} is meaningful
     *  only for whisper; ignore it for team/clan/buddy. */
    public static final class DecodedChat {
        public final int channel;
        public final int targetUid;
        public final String message;
        public DecodedChat(int channel, int targetUid, String message) {
            this.channel = channel;
            this.targetUid = targetUid;
            this.message = message;
        }
    }

    private Chat3bDecoder() {}

    /**
     * Decode the inner body of a {@code 0x03/0x1f tag=0x3b} packet.
     *
     * @param body bytes starting at the 0x03/0x1f inner data offset
     *        (i.e. {@code 02 00 3b ...} as the first three bytes)
     * @return decoded chat, or null if the body is malformed
     */
    public static DecodedChat decode(byte[] body) {
        if (body == null || body.length < 9) return null;
        // Expected layout: 02 00 3b [chan] [uid LE4] [msg + 00]
        if (body[2] != 0x3b) return null;
        int channel = body[3] & 0xff;
        int targetUid = ((body[4] & 0xff))
                      | ((body[5] & 0xff) << 8)
                      | ((body[6] & 0xff) << 16)
                      | ((body[7] & 0xff) << 24);
        // Find the null terminator at offset 8+.
        int end = body.length;
        for (int i = 8; i < body.length; i++) {
            if (body[i] == 0) { end = i; break; }
        }
        String msg = new String(body, 8, end - 8, StandardCharsets.US_ASCII);
        return new DecodedChat(channel, targetUid, msg);
    }
}
