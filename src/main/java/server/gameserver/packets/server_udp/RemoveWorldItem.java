package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Remove-world-item broadcast (UDP S→C reliable {@code 0x03/0x26}).
 *
 * <p>Sent to every player in the affected zone when an entity
 * (NPC, dropped item, drone) leaves the world — most commonly on
 * mob death. Triggers the client to despawn the model, retire
 * its slot from the local entity table, and stop drawing the
 * health bar / nameplate.
 *
 * <h3>Wire format</h3>
 *
 * <p>Body is a fixed 4 bytes: the entity_id as LE32. All 253
 * cataloged retail samples carry only this single field.
 *
 * <pre>
 *   sample #1  ce 03 00 00   entity 0x000003ce  (974)
 *   sample #2  b8 03 00 00   entity 0x000003b8  (952)
 *   sample #3  d5 03 00 00   entity 0x000003d5  (981)
 * </pre>
 *
 * <p>Catalog source: {@code docs/protocol/packets/udp_s2c_03_26.md}.
 * Top markers: KILL_MOB2, KILL_MOB, BEFORE_KILL_MOB23 — confirms
 * mob-death is the dominant trigger.
 */
public class RemoveWorldItem extends PacketBuilderUDP1303 {

    /**
     * Build a remove-world-item broadcast.
     *
     * @param recipient the player this packet is being sent to
     *                  (used by the parent for session counter +
     *                  cipher seed).
     * @param entityId  the world entity to despawn — typically an
     *                  NPC's mapID. Encoded as LE32.
     */
    public RemoveWorldItem(Player recipient, int entityId) {
        super(recipient);
        write(0x26);            // sub-opcode
        writeInt(entityId);     // [0..3] entity_id LE32
    }
}
