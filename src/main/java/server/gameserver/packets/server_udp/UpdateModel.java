package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class UpdateModel extends PacketBuilderUDP1303 {

	/** Self localId — the constant {@code 01 00} every retail
	 *  {@code 0x1f}/{@code 0x2f} packet addressed at the acting player
	 *  uses (NOT the map id). Same constant {@link EquipStateAck}
	 *  writes. */
	private static final int SELF_LOCAL_ID = 0x0001;

	/** TLV tag for the weapon model rendered IN HAND
	 *  ({@code 02 0a [modelId LE2]}). */
	public static final int TAG_HAND_MODEL = 0x0a;

	/** TLV tag for the weapon model rendered ON BACK
	 *  ({@code 02 0b [modelId LE2]}). */
	public static final int TAG_BACK_MODEL = 0x0b;

	/** Model-id sentinel meaning "render nothing" (unarmed / empty
	 *  slot). */
	public static final int MODEL_NONE = 0xffff;

	/**
	 * DELTA form of {@code 0x03/0x2f} UpdateModel — the missing half
	 * of the weapon-draw flow.
	 *
	 * <p>Where the {@link UpdateModel#UpdateModel(Player) full-state}
	 * form re-sends the whole body/face/name model on world entry,
	 * this delta tells the client ONLY which weapon model to render
	 * in-hand (and on-back) when the player equips a quickbelt slot.
	 * Retail emits it in the same reliable burst right after the
	 * {@link EquipStateAck} slot-state ack; without it the client has
	 * a slot selection but no model, so no weapon visibly appears.
	 *
	 * <p>Emitted bytes (inner body, after the reliable
	 * {@code [03][seq LE2]} wrapper):
	 * <pre>
	 *   2f 01 00 02 02 [stance LE2] 02 0a [handModelId LE2] 02 0b [backModelId LE2]
	 * </pre>
	 * The leading {@code 01 00} is the self localId (constant); the
	 * {@code 02 02 [stance LE2]} record is the stance/posture word
	 * (retail draw1 = {@code 02 02 00 00}). {@code 0xffff} in either
	 * weapon slot means "empty / unarmed".
	 *
	 * @param player   acting player (localId is the constant self id)
	 * @param handModelId weapon model to render in-hand, or
	 *                    {@code 0xffff} for none
	 * @param backModelId weapon model to render on-back, or
	 *                    {@code 0xffff} for none
	 */
	public UpdateModel(Player player, int handModelId, int backModelId) {
		super(player);
		// 0x2f sub-op + self localId (constant 01 00, NOT mapId).
		write(0x2f);
		writeShort(SELF_LOCAL_ID);

		// Stance / posture record — retail draw1 = 02 02 0000.
		write(0x02);
		write(0x02);
		writeShort(0x0000);

		// In-hand weapon model: 02 0a [modelId LE2].
		write(0x02);
		write(TAG_HAND_MODEL);
		writeShort(handModelId & 0xffff);

		// On-back weapon model: 02 0b [modelId LE2].
		write(0x02);
		write(TAG_BACK_MODEL);
		writeShort(backModelId & 0xffff);
	}

	/**
	 * HAND-ONLY delta — the exact shape retail emits when a weapon is
	 * DRAWN into hand (retail draw1, slot 0x01, pcap 2026-06-01):
	 * <pre>
	 *   2f 01 00 02 02 0000 02 0a [handModelId LE2]
	 * </pre>
	 * with NO trailing {@code 02 0b} on-back record. The earlier
	 * three-arg form always appended {@code 02 0b ffff}; retail's draw
	 * delta omits the back record entirely, so the client gets a clean
	 * "weapon in hand, nothing changed on back" update. Pass
	 * {@link #MODEL_NONE} to render nothing in hand (holster).
	 *
	 * @param player      acting player (localId is the constant self id)
	 * @param handModelId weapon model to render in-hand, or
	 *                    {@code 0xffff} for none
	 */
	public UpdateModel(Player player, int handModelId) {
		super(player);
		// 0x2f sub-op + self localId (constant 01 00, NOT mapId).
		write(0x2f);
		writeShort(SELF_LOCAL_ID);

		// Stance / posture record — retail draw1 = 02 02 0000.
		write(0x02);
		write(0x02);
		writeShort(0x0000);

		// In-hand weapon model only: 02 0a [modelId LE2]. No 02 0b.
		write(0x02);
		write(TAG_HAND_MODEL);
		writeShort(handModelId & 0xffff);
	}

	public UpdateModel(Player player) {
		super(player);
		PlayerCharacter pc = player.getCharacter();
		//packet start
		write(0x2f);
		writeShort(player.getMapID());

		//now follow the subpackets
		
		write(0x01); //unknown subpacket but always there except on dynamic updates
		write(0x00);
		write(0x20);

		write(0x02); //unknown
		write(0x01);
		write(0x07);
		
		write(0x02); //unknown
		write(0x05);
		write(0x8a); //8c/8a?
		
//		write(0x02); //unknown optional 
//		write(0x06);
//		write(0x0a);

		write(0x02); //unknown
		write(0x08);
		write(0x01); // 1/2

//		write(0x02); //weapon in hand optional (TAG_HAND_MODEL)
//		write(0x0a);
//		writeShort(0x0009); //weapon model id

//		write(0x02); //weapon on back optional (TAG_BACK_MODEL; send 0xffff to remove)
//		write(0x0b); // retail uses 0x0b here, NOT 0x0c
//		writeShort(0x0046); //weapon model id

		write(0x02); //hair model
		write(0x0d);
		writeShort(pc.getModel(PlayerCharacter.MODEL_HAIR));

		write(0x02); //beard model
		write(0x0e);
		writeShort(pc.getModel(PlayerCharacter.MODEL_BEARD));

		write(0x03); //model data
		write(0x00);
		write(0x0f); //size?
		writeShort(pc.getModel(PlayerCharacter.MODEL_HEAD));
		//writeShort(620);
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_HEAD));
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_TORSO));
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_LEG));
		writeShort(0); //unknown 0xe6e6 in hacknet
		writeShort(0); //unknown 0xe6e6 in hacknet
		writeShort(0); //unknown 0xe6e6 in hacknet
		writeShort(pc.getModel(PlayerCharacter.MODEL_TORSO));
		writeShort(pc.getModel(PlayerCharacter.MODEL_LEG));

		write(0x03); //name
		write(0x01);
		byte[] name = pc.getName().getBytes();
		write(name.length +1);
		write(name);
		write(0x00); //c-style

		write(0x03); //unknown
		write(0x03);
		write(0x00);
	}
}
