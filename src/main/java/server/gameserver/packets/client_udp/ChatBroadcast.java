package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.GameServer;
import server.gameserver.Player;
import server.gameserver.WorldMessageBus;
import server.gameserver.chat.Chat3bDecoder;
import server.gameserver.chat.ChatBroadcastIntent;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.tools.Out;

/**
 * Client-side decoder for {@code UDP 0x03/0x1f tag=0x3b} cross-channel
 * chat (whisper / team / clan / buddy).
 *
 * <p>The raw {@code subPacket} bytes start with the inner gamedata
 * wrapper {@code 03 [seq2] 1f}; the chat payload sits at offset 3 and
 * has the layout {@code 02 00 3b [chan] [target_uid LE4] [msg ASCII]}.
 *
 * <p>On {@link #execute(Player)}, the body is decoded via
 * {@link Chat3bDecoder} and a {@link ChatBroadcastIntent} is posted
 * to the configured {@link WorldMessageBus}. Fan-out to recipients
 * happens on the bus's drain thread (registered handler in
 * {@link server.gameserver.ChatManager}). Posting is deliberately
 * cheap so the producer's UDP-receive thread is not held up by
 * recipient lookups.
 */
public final class ChatBroadcast extends GamePacketDecoderUDP {

    /** Offset inside {@code subPacket} where the {@code 02 00 3b ...}
     *  body begins. The wrapper is {@code 03 [seq_lo seq_hi] 1f} = 4
     *  bytes; the body follows immediately. */
    static final int INNER_OFFSET = 4;

    private final WorldMessageBus bus;
    private final byte[] raw;

    public ChatBroadcast(byte[] subPacket) {
        this(subPacket, null);
    }

    /** Constructor that allows tests to inject a non-default bus.
     *  When {@code injectedBus} is null, {@link GameServer#getBus()}
     *  is consulted at execute time. */
    public ChatBroadcast(byte[] subPacket, WorldMessageBus injectedBus) {
        super(subPacket);
        this.raw = subPacket;
        this.bus = injectedBus;
    }

    @Override
    public void execute(Player pl) {
        if (raw == null || raw.length <= INNER_OFFSET) {
            Out.writeln(Out.Warning,
                    "ChatBroadcast: subPacket too short, dropping");
            return;
        }
        byte[] inner = new byte[raw.length - INNER_OFFSET];
        System.arraycopy(raw, INNER_OFFSET, inner, 0, inner.length);

        Chat3bDecoder.DecodedChat dec = Chat3bDecoder.decode(inner);
        if (dec == null) {
            Out.writeln(Out.Warning,
                    "ChatBroadcast: malformed body, dropping");
            return;
        }

        WorldMessageBus target = (bus != null) ? bus : GameServer.getBus();
        if (target == null) {
            Out.writeln(Out.Error,
                    "ChatBroadcast: no bus available, chat dropped");
            return;
        }

        PlayerCharacter pc = pl.getCharacter();
        if (pc == null) {
            Out.writeln(Out.Warning,
                    "ChatBroadcast: player has no character, dropping");
            return;
        }
        int senderUid = pc.getMisc(PlayerCharacter.MISC_ID);
        String senderName = pc.getName();

        target.post(new ChatBroadcastIntent(
                senderUid, senderName,
                dec.channel, dec.targetUid, dec.message));
    }
}
