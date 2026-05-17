package server.gameserver.packets.client_udp;

import server.database.accounts.Account;
import server.gameserver.Player;
import server.gameserver.command.commands.NoclipCommand;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.LocalChatMessage;
import server.tools.Out;

/**
 * Decoder for the native client's built-in GM commands that arrive on
 * the {@code 0x03/0x1f/<localId>/0x06} UDP path.
 *
 * <p>Typing {@code /gm_noclip} in the retail client does NOT send the
 * string — it emits this structured sub-packet. The framing follows
 * the documented {@code 0x1f} convention (same as {@code LocalChat},
 * {@code UseItem} etc.):
 * <pre>
 *   off 0   : 0x03            reliable wrapper
 *   off 1-2 : seq  (LE16)     reliable sequence
 *   off 3   : 0x1f            gamedata sub-opcode
 *   off 4-5 : localId (LE16)  client local-id slot
 *   off 6   : 0x06            GM-command tag
 *   off 7   : value (1 byte)  command argument (enabled = value != 0)
 * </pre>
 *
 * <p><b>Structured parse, not a scan.</b> An earlier external
 * reference handler scanned the buffer for the first {@code 0x06}
 * byte, which false-matches payload/coordinate bytes. This decoder
 * verifies the fixed offsets above before acting.
 *
 * <p><b>Permission-gated.</b> The native path is gated by the same
 * authority check as chat commands: the invoking account must have at
 * least {@link Account#GM_GAMEMASTER}. Without the gate, ANY client
 * could toggle noclip by sending this packet.
 */
public final class AdminCommandRequest extends GamePacketDecoderUDP {

    /** GM-command tag in the {@code 0x1f} family. */
    public static final int TAG_GM_COMMAND = 0x06;

    public AdminCommandRequest(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        if (pl == null) return;

        // Structured parse against the documented fixed offsets.
        skip(3);                 // 0x03 + seq LE16
        int sub = read();        // expect 0x1f
        skip(2);                 // localId LE16
        int tag = read();        // expect 0x06
        if (sub != 0x1f || tag != TAG_GM_COMMAND) {
            Out.writeln(Out.Error,
                    "AdminCommandRequest: malformed GM packet (sub=0x"
                            + Integer.toHexString(sub) + " tag=0x"
                            + Integer.toHexString(tag) + ") — ignored.");
            return;
        }
        int value = read();      // command argument byte
        if (value < 0) value = 0; // EOF guard

        // Permission gate — identical authority requirement to the
        // chat-command path. A client without GM authority that forges
        // this packet gets a denial, never the effect.
        Account acc = pl.getAccount();
        int level = acc == null ? 0 : acc.getGmLevel();
        if (level < Account.GM_GAMEMASTER) {
            Out.writeln(Out.Info,
                    "AdminCommandRequest: DENIED native GM command from "
                            + (pl.getCharacter() != null
                                    ? pl.getCharacter().getName()
                                    : "?")
                            + " (gm_level=" + level + ").");
            pl.send(new LocalChatMessage(pl,
                    "[Server] You are not a GM.", 2));
            return;
        }

        boolean enabled = value != 0;
        NoclipCommand.applyTo(pl, enabled);
        pl.send(new LocalChatMessage(pl,
                "[Server] gm_noclip " + (enabled ? "ENABLED" : "disabled")
                        + ".", 2));
    }
}
