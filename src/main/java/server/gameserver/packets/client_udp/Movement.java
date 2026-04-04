package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

public class Movement extends GamePacketDecoderUDP {

	public Movement(byte[] subPacket) {
		super(subPacket);
	}

	public void execute(Player pl) {
		PlayerCharacter pc = pl.getCharacter();

		skip(3);
		
		int type = read();
		if((type & 0x01) != 0) {
			pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, readShort() - 32000);
		}
		if((type & 0x02) != 0) {
			pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, readShort() - 32000);
		}
		if((type & 0x04) != 0) {
			pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, readShort() - 32000);
		}
		if((type & 0x08) != 0) {
			pc.setMisc(PlayerCharacter.MISC_TILT, read());//info_byte("up-mid-down(d6-80-2a)");
		}
		if((type & 0x10) != 0) {
			pc.setMisc(PlayerCharacter.MISC_ORIENTATION, read());//info_byte("s-e-n-w-s(0-45-90-135-180)");
		}
		if((type & 0x20) != 0) {
			pc.setMisc(PlayerCharacter.MISC_STATUS, read());//info_byte("0x02-kneeing 0x08-leftstep 0x10-rightstep 0x20-walking 0x40-forward 0x80-backward");
		}
		if((type & 0x40) != 0) {
			// ?? :(
		}
		pl.getZone().sendPlayerMovement(pl);
	}
}
