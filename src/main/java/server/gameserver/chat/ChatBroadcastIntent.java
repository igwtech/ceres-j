package server.gameserver.chat;

import server.gameserver.WorldMessageBus;

/**
 * Cross-player intent: broadcast a chat message via the bus.
 *
 * <p>Producer: a sub-tag handler for {@code 0x03/0x1f tag=0x3b}
 * decodes the C→S packet via {@link Chat3bDecoder}, builds an
 * intent, and posts it to {@link WorldMessageBus}.
 *
 * <p>Consumer: the chat manager registers a handler that, on each
 * intent, looks up the target audience (single recipient for
 * whisper, group/clan/buddy roster for the other channels) and
 * emits a {@link server.gameserver.packets.server_tcp.Chat8317}
 * to each recipient's TCP connection.
 *
 * <p>Concurrency note: the intent is posted by the sender's
 * UDP-receive thread but consumed by the single per-server tick
 * thread (zone -1 = global queue). This keeps fan-out off the hot
 * receive path and serializes chat ordering.
 */
public final class ChatBroadcastIntent implements WorldMessageBus.Intent {

    public final int senderUid;
    public final String senderName;
    public final int channel;
    public final int targetUid; // meaningful for whisper only
    public final String message;

    public ChatBroadcastIntent(int senderUid, String senderName,
                                int channel, int targetUid,
                                String message) {
        this.senderUid = senderUid;
        this.senderName = senderName;
        this.channel = channel;
        this.targetUid = targetUid;
        this.message = message;
    }

    /** Chat is global-scope on the bus (zone -1) — all chat fan-out
     *  needs the global player directory anyway. */
    @Override
    public int zoneId() { return -1; }
}
