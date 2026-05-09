package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Reliable {@code 0x03/0x2c} StartPos / position-and-appearance
 * packet. Carries the player's spawn position and full visible
 * character model (head, torso, leg model + texture indices).
 *
 * <h3>Wire format (verified 2026-05-09 against 4 retail pcaps:
 * HANNIBAL, NORMAN, AUGUSTO, DRSTONE3, all 72-byte minimal form)</h3>
 *
 * Body layout (post {@code [0x03][seq LE2]} wrapper):
 * <pre>
 *   [0]      0x2c                     CONSTANT sub-opcode
 *   [1]      0x01                     CONSTANT
 *   [2]      0x01                     CONSTANT (was {@code mapID-lo} in
 *                                      pre-fix code — happened to be 0x01 by
 *                                      accident when test mapID==1; broke
 *                                      for any other mapID)
 *   [3..6]   4-byte session state     varies per session — likely a packed
 *                                      entity ID or zone/spawn token. Pre-
 *                                      fix wrote {@code mapID-hi + 0x00 0x00
 *                                      0x00} (4 zeros for typical mapIDs),
 *                                      retail emits 4 non-zero bytes here.
 *                                      Plumbed as {@code mapID LE32} for now
 *                                      — runtime placeholder until the field
 *                                      semantic is pinned.
 *   [7..10]  Y coord float LE32       (tested {@code +1544}, {@code -533.9},
 *                                      {@code -2958}, {@code +7372} across
 *                                      4 retail captures — clean coords)
 *   [11..14] Z coord float LE32
 *   [15..18] X coord float LE32
 *   [19..28] 10 zero bytes             CONSTANT padding
 *   [29..30] LE16 trailer              varies per session (catalog samples:
 *                                      {@code 0x02d0}, {@code 0x02bc},
 *                                      {@code 0x0302} — likely zone/region
 *                                      hash; emitted as 0x0000 placeholder)
 * </pre>
 *
 * After byte 30, the body continues with the character model
 * section: head model, torso/leg/hair/beard models, texture
 * indices, class. Catalog reports min/avg/max sizes 71/190/826 B —
 * the larger forms append additional state for outfitted runners
 * (dual-wielded weapons, vehicles).
 *
 * <p><b>Pre-fix bug (resolved 2026-05-09):</b> Pre-fix code wrote
 * {@code write(new byte[]{0x2c,0x01}); writeShort(mapID); write(new
 * byte[]{0x00,0x00,0x00});} which put {@code mapID-lo} at body[2]
 * (where retail has constant 0x01) and {@code mapID-hi+3 zeros} at
 * body[3..6] (where retail has 4 var bytes of state). Pcap-replay
 * harness against DRSTONE3 surfaced the byte[2] divergence; verifying
 * against 4 captures via {@code tools/extract-1b-broadcasts.py}-style
 * one-off analysis confirmed the {@code 2c 01 01 [4 var]} layout.
 */
public class PositionUpdate extends PacketBuilderUDP1303 {

	public PositionUpdate(Player pl) {
		super(pl);

		PlayerCharacter pc = pl.getCharacter();
		write(new byte[] { 0x2c, 0x01, 0x01 });        // [0..2] constants
		writeInt((int) pl.getMapID());                  // [3..6] state LE32 (placeholder)
		writeFloat((float)pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));  // [7..10]
		writeFloat((float)pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));  // [11..14]
		writeFloat((float)pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));  // [15..18]
		write(new byte[] {                              // [19..28] 10 zeros
				  0x00, 0x00, 0x00, 0x00
				, 0x00, 0x00, 0x00, 0x00
				, 0x00, 0x00 });
		writeShort(0x0000);                              // [29..30] trailer placeholder
		// Character model section (head/torso/leg/hair/beard/class),
		// preserved from pre-fix layout — not byte-pinned to retail
		// yet (catalog avg 190B vs minimal 72B, tail bytes vary).
		writeShort(pc.getModel(PlayerCharacter.MODEL_HEAD));
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_HEAD));
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_TORSO));
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_LEG));
		write(new byte[] { //unknown
				  0x00, 0x00, 0x00, 0x00
				, 0x00, 0x00, 0x00, 0x00
				, 0x00, 0x00, 0x00, 0x00
				, 0x00, 0x00, 0x00, 0x00
				, 0x00, 0x00, 0x00, 0x00
				, 0x00
		});
		writeShort(pc.getModel(PlayerCharacter.MODEL_TORSO));
		writeShort(pc.getModel(PlayerCharacter.MODEL_LEG));
		writeShort(pc.getModel(PlayerCharacter.MODEL_HAIR));
		writeShort(pc.getModel(PlayerCharacter.MODEL_BEARD));
		write(pc.getMisc(PlayerCharacter.MISC_CLASS));
		writeShort(pc.getTexture(PlayerCharacter.TEXTURE_HEAD));
		writeShort(pc.getTexture(PlayerCharacter.TEXTURE_TORSO));
		writeShort(pc.getTexture(PlayerCharacter.TEXTURE_LEG));
	}

}
