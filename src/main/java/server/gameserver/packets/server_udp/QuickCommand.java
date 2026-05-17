package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Server-pushed "quick command" toggle on the {@code 0x1f → 0x3d}
 * channel.
 *
 * <p>The {@code 0x3d} family is the in-flight client/server physics
 * heartbeat channel (see {@code GamePacketReaderUDP.decodesub13},
 * case {@code 0x3d}, and {@code docs/PROTOCOL.md}). Sub-tag
 * {@code 0x11} is the local-physics toggle the client honours when
 * the server pushes it: a single value byte after the sub-tag flips
 * the named state on/off. The {@code noclip} GM command emits
 * {@code QuickCommand(pl, 0x11, 1)} to disable local collision and
 * {@code QuickCommand(pl, 0x11, 0)} to restore it.
 *
 * <p>Wire layout (after the {@code 0x03 [seq LE2]} reliable wrapper
 * added by {@link PacketBuilderUDP1303}):
 * <pre>
 *   1f 00 00 3d [sub] [value]
 * </pre>
 * The {@code 00 00} after {@code 0x1f} is the local-id slot the
 * client uses for all {@code 0x1f} gamedata frames (mirrors the
 * C→S framing the reader skips with {@code pd.skip(2)}).
 */
public class QuickCommand extends PacketBuilderUDP1303 {

    /** Sub-tag {@code 0x11}: toggle local physics / collision. */
    public static final int SUB_LOCAL_PHYSICS = 0x11;

    public QuickCommand(Player pl, int subTag, int value) {
        super(pl);
        write(0x1f);
        write(0x00);
        write(0x00);
        write(0x3d);
        write(subTag & 0xff);
        write(value & 0xff);
    }
}
