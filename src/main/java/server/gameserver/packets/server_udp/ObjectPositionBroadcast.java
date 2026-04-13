package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Raw (unreliable) {@code 0x1b} object-position broadcast.
 *
 * <p>Wire format (19 bytes total) reverse-engineered from 122 samples in
 * retail capture ACC1_CHAR1 — constant-byte positions confirmed across
 * all captures:
 *
 * <pre>
 *   offset 0  : 0x1b              sub-packet type
 *   offset 1  : objectId byte     per-object rotating id (varies 0-255)
 *   offset 2  : 0x01              constant (object class marker)
 *   offset 3-4: 0x00 0x00         constant padding
 *   offset 5  : 0x1f              constant inner opcode
 *   offset 6-7: Y LE uint16       (Y + 32000), signed world coord
 *   offset 8-9: Z LE uint16       (Z + 32000)
 *   offset 10-11: X LE uint16     (X + 32000)
 *   offset 12 : orientation byte  (0x40 is default heading in samples)
 *   offset 13-14: status/flags    2 bytes
 *   offset 15-16: 0x00 0x00       constant padding
 *   offset 17-18: trailer         2 bytes (0x11 0x0f / 0x11 0x11 dominant)
 * </pre>
 *
 * <p><b>Why this exists:</b> retail servers broadcast 122 of these per
 * session (7.7 Hz) and without them the modern NCE 2.5 client's
 * world-alive watchdog expires after ~10-25 seconds of playing, the
 * "SYNCHRONIZING INTO CITY ZONE" overlay re-appears, and the session
 * times out. It's unreliable (no {@code 0x03} wrapper) so it does NOT
 * cause position rubberbanding the way a reliable
 * {@code 0x03→0x1b PlayerPositionUpdate} echo does. See
 * {@code docs/retail_burst_analysis.md} and the diff comparison in
 * memory {@code raw_1b_broadcast.md}.
 *
 * <p>This packet carries the moving player's own position but with a
 * synthetic {@code objectId} distinct from the player's map id, so the
 * client treats it as a generic zone object broadcast (the watchdog
 * input) rather than a self-position override.
 */
public class ObjectPositionBroadcast extends PacketBuilderUDP13 {

    /**
     * Fixed object id for a phantom NPC standing still far from the
     * player's spawn. Using a CONSTANT id instead of a rotating one
     * because the first attempt used {@code counter ^ 0xF0} which
     * generated ~50 unique "objects" per session — the client queried
     * 13 of them with RequestWorldInfo and gave up when we had no
     * WorldInfo reply to send. Retail sessions use ~19 stable object
     * ids, each broadcast ~8 times. One consistent phantom is simpler
     * and avoids spurious RequestWorldInfo pressure we can't satisfy.
     */
    private static final int PHANTOM_ID = 0xAB;

    /**
     * Fixed world position for the phantom, chosen far from player
     * spawn so the client can never interpret it as a collider near the
     * player. First broadcast-attempt used player's own coordinates,
     * which halved the movement-packet rate (148 -> 32) — broadcasting
     * a phantom at the player's location confuses the client's local
     * movement / collision code.
     */
    private static final int PHANTOM_Y = 30000;
    private static final int PHANTOM_Z = 0;
    private static final int PHANTOM_X = 30000;

    public ObjectPositionBroadcast(Player pl) {
        super(pl);

        write(0x1b);                                  // [0] type
        write(PHANTOM_ID);                            // [1] stable object id
        write(0x01);                                  // [2] class marker
        write(0x00);                                  // [3]
        write(0x00);                                  // [4]
        write(0x1f);                                  // [5] inner opcode

        writeShort((PHANTOM_Y + 32000) & 0xFFFF);     // [6-7] Y
        writeShort((PHANTOM_Z + 32000) & 0xFFFF);     // [8-9] Z
        writeShort((PHANTOM_X + 32000) & 0xFFFF);     // [10-11] X

        write(0x40);                                  // [12] orient (dominant in retail)

        write(0x00);                                  // [13] status lo
        write(0x00);                                  // [14] status hi
        write(0x00);                                  // [15]
        write(0x00);                                  // [16]

        // Trailer: 0x11 0x11 observed in 50/122 (41%) retail samples;
        // 0x11 0x0f in another 48 (39%). Content is almost certainly a
        // session-local marker — we pick the most common.
        write(0x11);                                  // [17]
        write(0x11);                                  // [18]

        // Suppress "pc unused" warning — kept as a parameter above so
        // a future revision can pull player / character state back in
        // once the watchdog behavior is fully characterized.
        @SuppressWarnings("unused")
        PlayerCharacter pc = pl.getCharacter();
    }
}
