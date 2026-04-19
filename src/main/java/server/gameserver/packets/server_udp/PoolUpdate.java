package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Raw {@code 0x1f} pool value update (HP/PSI/STA).
 *
 * <p>Retail format (16 bytes):
 * <pre>
 *   1f 01 00 50 [value LE4] [00 00 00] [pool_type] [max_value LE2] [00 00]
 * </pre>
 *
 * <p>Pool types: 0x04 = HP, 0x05 = PSI, 0x06 = STA (tentative).
 * Setting value to 0 or negative triggers death in the character system.
 */
public class PoolUpdate extends PacketBuilderUDP13 {

    public static final int POOL_HP = 0x04;
    public static final int POOL_PSI = 0x05;
    public static final int POOL_STA = 0x06;

    public PoolUpdate(Player pl, int poolType, int value, int maxValue) {
        super(pl);
        write(0x1f);
        write(0x01);
        write(0x00);
        write(0x50);           // set-pool sub-opcode
        writeInt(value);       // current value (can be negative for death)
        write(0x00);
        write(0x00);
        write(0x00);
        write(poolType);       // pool type (0x04=HP, 0x05=PSI)
        writeShort(maxValue);  // max value
        write(0x00);
        write(0x00);
    }
}
