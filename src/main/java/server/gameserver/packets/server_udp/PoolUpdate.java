package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Raw {@code 0x1f} pool delta packet (HP/PSI/STA).
 *
 * <p>Retail format (16 bytes):
 * <pre>
 *   1f 01 00 50 [delta LE4 signed] [00 00 00] [pool_type] [max_value LE2] [00 00]
 * </pre>
 *
 * <p>Verified from retail death capture: at fatal hit the server sends
 * {@code 1f 01 00 50 ea fe ff ff 00 00 00 04 8c 01 00 00} —
 * delta = -278 (HP), pool_type = 0x04, max = 396 (0x18c).
 *
 * <p>The value field is a <b>signed delta</b> applied to the client's
 * current pool value, NOT an absolute level. Positive values heal,
 * negative values damage.
 *
 * <p>Pool types: 0x04 = HP, 0x05 = PSI, 0x06 = STA.
 *
 * @see <a href="../../../../../docs/PACKET_REFERENCE.md">PACKET_REFERENCE.md</a>
 *      section 5.5 "raw 0x1f 16B -- Pool Delta (combat only)"
 */
public class PoolUpdate extends PacketBuilderUDP13 {

    public static final int POOL_HP = 0x04;
    public static final int POOL_PSI = 0x05;
    public static final int POOL_STA = 0x06;

    /**
     * @param pl       the player receiving the pool change
     * @param poolType {@link #POOL_HP}, {@link #POOL_PSI}, or {@link #POOL_STA}
     * @param delta    signed delta to apply (negative = damage, positive = heal)
     * @param maxValue the max value of the pool, used by the client to clamp
     */
    public PoolUpdate(Player pl, int poolType, int delta, int maxValue) {
        super(pl);
        write(0x1f);
        write(0x01);
        write(0x00);
        write(0x50);           // pool-delta sub-opcode
        writeInt(delta);       // signed delta (negative = damage)
        write(0x00);
        write(0x00);
        write(0x00);
        write(poolType);       // pool type (0x04=HP, 0x05=PSI, 0x06=STA)
        writeShort(maxValue);  // max value
        write(0x00);
        write(0x00);
    }
}
