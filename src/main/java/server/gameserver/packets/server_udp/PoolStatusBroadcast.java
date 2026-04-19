package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Raw {@code 0x1f} pool status broadcast — server tells the client
 * its current HP/PSI/STA values are valid.
 *
 * <p>Retail sends 54 of these per 60-second session (~0.9/s). Two
 * variants observed:
 * <ul>
 *   <li>14B: {@code 1f 01 00 30 8a [hp_lo hp_hi] [psi_lo psi_hi]
 *            [sta_lo sta_hi] [max_sta_lo max_sta_hi]}</li>
 *   <li>8B: {@code 1f [counter LE2] 56 00 00 00 00}</li>
 * </ul>
 *
 * <p>Without periodic pool status broadcasts, the client may consider
 * its character state stale and force a re-sync.
 */
public class PoolStatusBroadcast extends PacketBuilderUDP13 {

    public PoolStatusBroadcast(Player pl) {
        super(pl);
        PlayerCharacter pc = pl.getCharacter();

        // Retail format (14B total): 1f 01 00 30 [HP LE2] [PSI LE2]
        //   [STA LE2] [maxHP LE2] [maxHP LE2]
        // The 0x8a that was previously treated as a sub-opcode byte
        // is actually the LOW byte of HP (138 = 0x8a). Fixed.
        write(0x1f);  // GamePackets sub-type
        write(0x01);  // variant
        write(0x00);
        write(0x30);  // pool status sub-opcode
        writeShort(pc.getHealth());      // current HP
        writeShort(pc.getPsi());         // current PSI
        writeShort(pc.getStamina());     // current STA
        writeShort(pc.getMaxHealth());   // max HP
        writeShort(pc.getMaxHealth());   // max HP (repeated in retail)
    }
}
