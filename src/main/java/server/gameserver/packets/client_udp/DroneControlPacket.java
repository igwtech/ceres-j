package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.GameServer;
import server.gameserver.Player;
import server.gameserver.WorldMessageBus;
import server.gameserver.npc.DroneControlDecoder;
import server.gameserver.npc.DroneControlIntent;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.tools.Out;

/**
 * Client-side decoder for {@code UDP C->S 0x03/0x2d} drone-control
 * frames (41 bytes including wrapper, 37 bytes inner).
 *
 * <p>Strips the {@code 03 [seq2] 2d} wrapper, decodes via
 * {@link DroneControlDecoder}, and posts a
 * {@link DroneControlIntent} carrying the pilot UID + drone position.
 * The 5-byte heartbeat variant is logged but otherwise dropped — the
 * server has no state machine for it yet (Phase 3 will register it).
 *
 * <p>Routed through the {@link server.gameserver.packets.SubtagRouter}
 * at {@code 0x03/0x2d/-1}.
 */
public final class DroneControlPacket extends GamePacketDecoderUDP {

    /** Offset inside {@code subPacket} where the inner body starts.
     *  Wrapper layout: {@code 03 [seq2] 2d} = 4 bytes. */
    static final int INNER_OFFSET = 4;

    private final WorldMessageBus injectedBus;
    private final byte[] raw;

    public DroneControlPacket(byte[] subPacket) {
        this(subPacket, null);
    }

    public DroneControlPacket(byte[] subPacket, WorldMessageBus injectedBus) {
        super(subPacket);
        this.raw = subPacket;
        this.injectedBus = injectedBus;
    }

    @Override
    public void execute(Player pl) {
        if (raw == null || raw.length <= INNER_OFFSET) return;
        byte[] inner = new byte[raw.length - INNER_OFFSET];
        System.arraycopy(raw, INNER_OFFSET, inner, 0, inner.length);

        if (inner.length == DroneControlDecoder.HEARTBEAT_LEN) {
            // Heartbeat — purpose still TBD. Log only.
            DroneControlDecoder.DroneHeartbeat h =
                    DroneControlDecoder.decodeHeartbeat(inner);
            if (h != null) {
                Out.writeln(Out.Info,
                    "DroneControlPacket: heartbeat drone=" + h.droneId
                    + " status=0x" + Integer.toHexString(h.statusByte));
            }
            return;
        }

        DroneControlDecoder.DroneControl c =
                DroneControlDecoder.decodeControl(inner);
        if (c == null) {
            Out.writeln(Out.Warning,
                "DroneControlPacket: malformed body length=" + inner.length);
            return;
        }

        WorldMessageBus bus = (injectedBus != null)
                ? injectedBus : GameServer.getBus();
        if (bus == null) return;

        PlayerCharacter pc = pl.getCharacter();
        if (pc == null) return;
        int pilotUid = pc.getMisc(PlayerCharacter.MISC_ID);
        bus.post(new DroneControlIntent(pilotUid, c.droneId,
                c.posX, c.posY, c.posZ, c.tail));
    }
}
