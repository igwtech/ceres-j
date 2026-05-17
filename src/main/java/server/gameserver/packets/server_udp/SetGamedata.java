package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Server-pushed named gamedata variable on the {@code 0x1f → 0x3e}
 * channel.
 *
 * <p>The native client keeps a string-keyed gamedata table that its
 * Lua layer reads (see {@code project_lua_bridge} in project memory:
 * {@code dialogheader.lua} / gamedata RPC). Setting {@code gm_noclip}
 * to {@code 1.0f} is what the retail GM build does to mark a session
 * as flying so client-side scripts stop running ground-snap logic.
 *
 * <p>Wire layout (after the {@code 0x03 [seq LE2]} reliable wrapper):
 * <pre>
 *   1f 00 00 3e [keyLen 1B] [key ASCII, NUL-terminated] [value LE float]
 * </pre>
 * The {@code 00 00} after {@code 0x1f} is the same local-id slot all
 * {@code 0x1f} gamedata frames carry. {@code keyLen} counts the key
 * bytes including the trailing NUL (matches the C-string length
 * convention used elsewhere, e.g. {@code UpdateModel} name field).
 */
public class SetGamedata extends PacketBuilderUDP1303 {

    public SetGamedata(Player pl, String key, float value) {
        super(pl);
        write(0x1f);
        write(0x00);
        write(0x00);
        write(0x3e);
        byte[] k = key.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        write(k.length + 1);
        write(k);
        write(0x00); // C-style terminator
        writeFloat(value);
    }
}
