package server.gameserver.npc;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Server-side builder for {@code UDP S->C 0x03/0x2d} (Reliable /
 * NPCData) <strong>long</strong> variant — the 54-byte packet that
 * carries a single mob/NPC's state and broadcast position.
 *
 * <p>Wire layout (post-{@code 03/seq2/2d} wrapper, 50 bytes):
 *
 * <pre>
 *   [npc_id LE32] [state] [flags] [altitude LE32 float]
 *   [target_id LE32] [tail 36B]
 * </pre>
 *
 * <p>Tests cover byte-equal reproduction of the retail combat sample
 * {@code 53010000 71 40 426a3a45 ffffffff 01 b5 282a0000 00aac200 00aac2 a8f92f01 97}
 * (truncated at 32 bytes in the catalog evidence; the remaining 18
 * bytes form the opaque tail).
 *
 * <p>The 9-byte heartbeat variant remains the responsibility of
 * {@link server.gameserver.packets.server_udp.NpcDataBroadcast}; this
 * class is only the long form.
 */
public final class MobStateBroadcast extends PacketBuilderUDP1303 {

    /** Default opaque-tail filler (zero bytes) used when the caller
     *  doesn't pass real captured tail bytes. */
    public static final byte[] ZERO_TAIL = new byte[36];

    /**
     * @param pl       owning player connection (sequence counter source)
     * @param npcId    32-bit entity id (LE on the wire)
     * @param state    mob state
     * @param flags    state-dependent modifier byte at offset 5
     * @param altitude position altitude float at offset 6-9
     * @param targetId target entity id, or {@link MobDataDecoder#NO_TARGET}
     * @param tail     36 bytes of opaque trailer (offsets 14..49). May
     *                 be {@link #ZERO_TAIL} if the caller has no
     *                 ground-truth bytes; the client tolerates zero
     *                 fill in the trailing slots in our own captures
     *                 (the trailer carries position deltas / model
     *                 metadata that the engine refreshes on next tick).
     * @throws IllegalArgumentException if tail is not exactly 36 bytes
     */
    public MobStateBroadcast(Player pl, int npcId, MobState state,
                              int flags, float altitude, int targetId,
                              byte[] tail) {
        super(pl);
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (tail == null || tail.length != 36) {
            throw new IllegalArgumentException(
                "tail must be exactly 36 bytes, got "
                + (tail == null ? -1 : tail.length));
        }
        write(0x2d);                              // sub-type
        writeIntLE(npcId);
        write(state.wireByte());
        write(flags & 0xff);
        writeIntLE(Float.floatToRawIntBits(altitude));
        writeIntLE(targetId);
        for (byte b : tail) write(b & 0xff);
    }

    private void writeIntLE(int v) {
        write(v        & 0xff);
        write((v >> 8 ) & 0xff);
        write((v >> 16) & 0xff);
        write((v >> 24) & 0xff);
    }
}
