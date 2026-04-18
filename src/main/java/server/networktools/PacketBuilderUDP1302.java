package server.networktools;

import server.gameserver.Player;

/**
 * Builds a {@code 0x13} gamedata packet with a {@code 0x02} "simplified
 * reliable" wrapper — the unreliable counterpart to {@link PacketBuilderUDP1303}.
 *
 * <p>Retail uses the {@code 0x02} wrapper for initialization data that
 * the client needs exactly once (UpdateModel, Weather, InfoResponse,
 * GamePackets/Soullight). Wire format is identical to {@code 0x03}:
 * <pre>
 *   [0x02][seq_lo][seq_hi][inner_sub_type][inner_data...]
 * </pre>
 *
 * <p>The key difference from {@code 0x03} is that {@code 0x02} packets
 * are NOT ACKed by the client's reliable-delivery system. They're
 * fire-and-forget initialization data. Retail sends them in a batch
 * right after the CharInfo multipart burst.
 */
public class PacketBuilderUDP1302 extends PacketBuilderUDP13 {

    private static int seq = 1;

    public PacketBuilderUDP1302(Player pl) {
        super(pl);
        write(2);  // 0x02 wrapper (simplified reliable)
        writeShort(seq++);
    }
}
