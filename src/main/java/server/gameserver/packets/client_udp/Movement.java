package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.ecs.Components;
import server.ecs.EcsRegistry;
import server.ecs.World;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

/**
 * Client-&gt;server movement packet.
 *
 * <p>Proof-of-concept subsystem for the light ECS: position / orientation / status
 * are written into the ECS component arrays first and then synced back to the
 * legacy {@link PlayerCharacter}. The rest of the codebase (zone broadcast,
 * persistence) still reads from {@code PlayerCharacter} so the port is incremental.
 */
public class Movement extends GamePacketDecoderUDP {

	public Movement(byte[] subPacket) {
		super(subPacket);
	}

	public void execute(Player pl) {
		PlayerCharacter pc = pl.getCharacter();

		skip(3);

		int type = read();

		long handle = pl.getEcsEntity();
		World world = EcsRegistry.world();
		Components c = EcsRegistry.components();
		boolean useEcs = world.isAlive(handle);
		int e = useEcs ? World.index(handle) : -1;

		if ((type & 0x01) != 0) {
			int v = readShort() - 32000;
			if (useEcs) c.posY.set(e, v);
			pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, v);
		}
		if ((type & 0x02) != 0) {
			int v = readShort() - 32000;
			if (useEcs) c.posZ.set(e, v);
			pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, v);
		}
		if ((type & 0x04) != 0) {
			int v = readShort() - 32000;
			if (useEcs) c.posX.set(e, v);
			pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, v);
		}
		if ((type & 0x08) != 0) {
			int v = read();//info_byte("up-mid-down(d6-80-2a)");
			if (useEcs) c.tilt.set(e, v);
			pc.setMisc(PlayerCharacter.MISC_TILT, v);
		}
		if ((type & 0x10) != 0) {
			int v = read();//info_byte("s-e-n-w-s(0-45-90-135-180)");
			if (useEcs) c.orientation.set(e, v);
			pc.setMisc(PlayerCharacter.MISC_ORIENTATION, v);
		}
		if ((type & 0x20) != 0) {
			int v = read();//info_byte("0x02-kneeing 0x08-leftstep 0x10-rightstep 0x20-walking 0x40-forward 0x80-backward");
			if (useEcs) c.status.set(e, v);
			pc.setMisc(PlayerCharacter.MISC_STATUS, v);
		}
		if ((type & 0x40) != 0) {
			// ?? :(
		}
		pl.getZone().sendPlayerMovement(pl);

		// Previously attempted: echo a reliable 0x03->0x1b PlayerPositionUpdate
		// back to the moving player every 500 ms. That REGRESSED movement
		// (rubberbanding): the reliable position update overrides the
		// client's local prediction, so the client got snapped back each
		// tick and the gameplay loop starved (0x1f events 448 -> 9).
		// Retail's 20-25 reliable 0x03->0x1b per session turn out to be
		// a one-shot burst during zone population, not periodic echoes.
		// Leaving the logic off here; the real fix for the 10-15 s
		// "re-sync" is more likely a different packet type (raw 0x1b
		// unreliable broadcast, or a session-alive marker), not a
		// reliable self-echo.
	}
}
