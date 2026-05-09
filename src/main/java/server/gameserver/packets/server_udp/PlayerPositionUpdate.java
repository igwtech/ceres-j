package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Server-authoritative position echo (UDP S→C reliable
 * {@code 0x03/0x1b}). The modern NCE 2.5 client requires this
 * packet at world-entry to clear its "Synchronizing into city
 * zone" overlay — without it the local prediction window
 * expires after ~10–15 s and the watchdog times the session
 * out.
 *
 * <h3>Wire format (catalog evidence: {@code docs/protocol/packets/udp_s2c_03_1b.md})</h3>
 *
 * <p>11-byte minimal form derived from 3 retail samples (all
 * identical except entity_id):
 *
 * <pre>
 *   sample #1  c9 00 00 00 20 00 00 00 00 ff 14   entity 0x00c9
 *   sample #2  c8 00 00 00 20 00 00 00 00 ff 14   entity 0x00c8
 *   sample #3  9f 00 00 00 20 00 00 00 00 ff 14   entity 0x009f
 * </pre>
 *
 * <pre>
 *   offset 0..1 : entity_id LE16   varies (target map id)
 *   offset 2..3 : 0x00 0x00        reserved
 *   offset 4    : 0x20             marker (constant — distinguishes
 *                                   reliable position-authority echo
 *                                   from the unreliable 0x1b broadcast)
 *   offset 5..8 : 0x00 0x00 0x00 0x00  padding
 *   offset 9    : 0xff             status flags (constant in samples)
 *   offset 10   : 0x14             animation/action enum (constant)
 * </pre>
 *
 * <p>Cataloged sizes range 11–46 B (avg 16 B). The longer
 * variants append position+orientation correction; full byte
 * structure for those is not yet pinned to retail capture hex
 * — emitting the 11-byte minimal form satisfies the watchdog
 * without speculation about the extended layout.
 *
 * <h3>Pre-fix bug</h3>
 *
 * <p>The previous emitter wrote the marker byte at offset 4 as
 * {@code 0x03} (wrong) and always tried to extend with position
 * floats in the wrong order, with hardcoded {@code 0x7d} bytes
 * inserted between fields. Three samples of retail evidence vs.
 * the old 17-byte payload showed every byte from offset 4
 * onward diverged. The client therefore never accepted any of
 * these as valid position-authority — which is the actual root
 * cause of the persistent "Synchronizing" overlay.
 */
public class PlayerPositionUpdate extends PacketBuilderUDP1303 {

    public PlayerPositionUpdate(Player pl, PlayerCharacter pc, int mapID) {
        super(pl);
        write(0x1b);                            // sub-opcode
        writeShort(mapID & 0xFFFF);             // [0..1]  entity_id LE16
        write(0x00);                            // [2]     reserved
        write(0x00);                            // [3]     reserved
        write(0x20);                            // [4]     marker (position-authority)
        write(0x00);                            // [5]     padding
        write(0x00);                            // [6]     padding
        write(0x00);                            // [7]     padding
        write(0x00);                            // [8]     padding
        write(0xff);                            // [9]     status flags
        write(0x14);                            // [10]    animation/action
    }
}
