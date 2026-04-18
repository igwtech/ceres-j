package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1302;

/**
 * Player visual model initialization via the {@code 0x02} wrapper.
 *
 * <p>Retail sends {@code 0x02→0x2f} with the player's appearance model
 * during the initial burst. This is the same data as {@link UpdateModel}
 * but sent via the {@code 0x02} wrapper instead of {@code 0x03}.
 *
 * <p>Retail inner payload (69-77B): model parts, textures, hair, beard,
 * character name — same structure as the 0x03→0x2f UpdateModel.
 */
public class InitUpdateModel02 extends PacketBuilderUDP1302 {
    public InitUpdateModel02(Player pl) {
        super(pl);
        PlayerCharacter pc = pl.getCharacter();

        write(0x2f); // UpdateModel sub-type
        writeShort(pl.getMapID());

        // Fixed sub-block structure (same as UpdateModel.java)
        write(new byte[]{0x01, 0x00, 0x20});
        write(new byte[]{0x02, 0x01, 0x07});
        write(new byte[]{0x02, 0x05, (byte) 0x8a});
        write(new byte[]{0x02, 0x08, 0x01});

        // Hair model
        write(0x02); write(0x0d);
        writeShort(pc.getModel(PlayerCharacter.MODEL_HAIR));

        // Beard model
        write(0x02); write(0x0e);
        writeShort(pc.getModel(PlayerCharacter.MODEL_BEARD));

        // Head + textures + torso/leg models
        write(0x03); write(0x00); write(0x0f);
        writeShort(pc.getModel(PlayerCharacter.MODEL_HEAD));
        write(pc.getTextureIndex(PlayerCharacter.TEXTURE_HEAD));
        write(pc.getTextureIndex(PlayerCharacter.TEXTURE_TORSO));
        write(pc.getTextureIndex(PlayerCharacter.TEXTURE_LEG));
        write(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        writeShort(pc.getModel(PlayerCharacter.MODEL_TORSO));
        writeShort(pc.getModel(PlayerCharacter.MODEL_LEG));

        // Character name
        byte[] nameBytes = pc.getName().getBytes();
        write(0x03); write(0x01);
        write(nameBytes.length + 1);
        write(nameBytes);
        write(0x00); // null terminator

        // Trailing marker
        write(new byte[]{0x03, 0x03, 0x00});
    }
}
