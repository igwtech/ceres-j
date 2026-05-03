package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.inventory.PlayerInventory;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.SZoning1;

/**
 * Client zone-edge crossing notification (reliable {@code 0x03/0x22}
 * sub {@code 0x0d}). Modern NCE 2.5.x client fires this when the
 * player walks into a BSP zone-trigger volume; the destination zone_id
 * is in the body.
 *
 * <p>Restored to the legacy SZoning1 response while we work out the
 * full retail two-phase flow (Zoning1 + despawn burst → Zoning2 →
 * TCP transition). The legacy SZoning1 path produces a brief
 * "Synchronizing" splash + mid-zone teleport rather than retail's
 * smooth swap, but at least gameplay continues. See
 * docs/zoning_protocol_2026-05-02.md for the full retail flow we
 * still need to implement.
 */
public class Zoning1 extends GamePacketDecoderUDP {

	public Zoning1(byte[] subPacket) {
		super(subPacket);
	}

	public void execute(Player pl) {
		skip(14);
		pl.getCharacter().setMisc(PlayerCharacter.MISC_LOCATION, readInt());
		pl.updateZone();
		((PlayerInventory)pl.getCharacter().getContainer(PlayerCharacter.PLAYERCONTAINER_F2)).doSort();
		pl.send(new SZoning1(readInt(), pl));
	}
}
