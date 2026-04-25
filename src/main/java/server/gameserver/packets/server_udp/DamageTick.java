package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Short combat-tick damage notification ({@code 1f 01 00 25 23 30}).
 *
 * <p>Retail emits this 6-byte payload roughly every 500 ms while a
 * character is taking damage from any source — observed in the
 * {@code RETAIL_DEATH} capture {@link
 * tools/extract_pool_events.py} as the {@code DAMAGE_EVENT 0x25
 * sub=0x23} hits during the ~30 s leading up to the fatal blow.
 *
 * <p>Unlike {@link DamageEvent} (the rich {@code 0x25 0x06} variant
 * with target/attacker/value floats that retail only sends at the
 * killing blow), this short tick has no payload — it appears to be a
 * "damage occurring" pulse that drives the client's HUD HP-bar
 * animation. Without it, our HUD doesn't tick down despite the server
 * applying damage internally.
 *
 * <p>Wire format (6 bytes inner, 0x03 reliable wrapper adds the rest):
 * <pre>
 *   1f 01 00 25 23 30
 * </pre>
 */
public class DamageTick extends PacketBuilderUDP1303 {

    public DamageTick(Player pl) {
        super(pl);
        write(0x1f);
        write(0x01);
        write(0x00);
        write(0x25);
        write(0x23);
        write(0x30);
    }
}
