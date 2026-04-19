package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Combat damage event: {@code 0x03 → 0x1f → mapId → 0x25 0x06}.
 *
 * <p>Retail format (27 bytes inner after 0x1f):
 * <pre>
 *   [mapId LE2] [0x25] [0x06] [0x01] [0x05] [dmg_type] [0x00]
 *   [target_id LE2] [0x01] [damage float LE4]
 *   [target_id LE2] [attacker_id LE2]
 *   [0x00 0x00] [attacker_wid LE2] [0x00 0x00]
 *   [splash? float LE4]
 * </pre>
 *
 * <p>The client's character system processes this, applies damage to HP,
 * and triggers the death screen when HP reaches 0.
 */
public class DamageEvent extends PacketBuilderUDP1303 {

    /**
     * @param pl        the player receiving damage
     * @param damage    damage amount as float
     * @param attackerId the attacker's mapId (NPC world ID)
     * @param dmgType   damage type (0x0a = energy, 0x00 = physical)
     */
    public DamageEvent(Player pl, float damage, int attackerId, int dmgType) {
        super(pl);
        PlayerCharacter pc = pl.getCharacter();
        // Use the player's zone mapId as the target entity. The client's
        // character system maps entities by this ID, not the database charId.
        // When this was set to pc.getMisc(MISC_ID), the damage bubbles
        // appeared at NPC positions instead of the player.
        int targetId = pl.getMapID() & 0xFFFF;

        write(0x1f);                       // GamePackets
        writeShort(pl.getMapID());         // zone mapId
        write(0x25);                       // combat opcode
        write(0x06);                       // damage sub-opcode
        write(0x01);                       // flags
        write(0x05);                       // sub-flags
        write(dmgType & 0xFF);             // damage type
        write(0x00);                       // padding
        writeShort(targetId);              // target entity
        write(0x01);                       // hit flag
        writeFloat(damage);               // damage amount
        writeShort(targetId);              // target (repeat)
        writeShort(attackerId);            // attacker mapId
        write(0x00); write(0x00);          // padding
        writeShort(0x0673);                // unknown field (retail: 73 06)
        write(0x00); write(0x00);          // padding
        writeShort(0x4120);                // trailing field (retail: 20 41)
    }
}
