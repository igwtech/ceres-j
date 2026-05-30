package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.tools.Out;

/**
 * C→S {@code 0x03/0x1f/<mapId LE2>/0x2f} — Genrep map "pick" event.
 *
 * <h3>Wire format (live retail capture 2026-05-29 iter 45)</h3>
 *
 * <pre>
 *   03 [seq:LE2]                    reliable wrapper
 *   1f [mapId:LE2]                  event class + sender's mapId
 *   2f                              sub-tag (map-pick)
 *   ff ff ff ff ff ff ff ff         8B all-FF sentinel
 * </pre>
 *
 * Total: 5B sub-header + 9B body = 14B sub-packet.
 *
 * <p>Fires when the player opens the Genrep map overlay from the
 * death screen (E key) and "picks" — server interprets this as the
 * full GR-teleport request. The all-FF sentinel in the body says
 * "pick any available GR" (vs an explicit station_id).
 *
 * <p>The full retail flow after this packet
 * (see {@code memory/genrep_teleport_full_sequence.md}):
 *
 * <pre>
 *   S→C  0x03/0x23 InfoResponse + ffffffff [entity_id:LE32]
 *   S→C  0x03/0x22/0x03 Zoning1 close-handshake
 *   S→C  TCP 0x83/0x0d Zoning2 (7B)
 *   S→C  TCP 0x83/0x0c Location → "apps/vr_app_1\0" (or other GR target)
 *   C→S  0x03/0x08 ReliableAck (auto by reliable layer)
 *   C→S  0x03/0x2a RequestInitBurst
 * </pre>
 *
 * <p><strong>Current implementation</strong>: this handler is an
 * observation-only stub. It logs the event so we can confirm the
 * client emits it (#281 silent-accept goal), but does NOT yet
 * trigger the full teleport flow. Future work (#203):
 *
 * <ol>
 *   <li>Resolve the player's faction default-GR (or nearest active
 *       GR) from the appplaces / GR-stations table.</li>
 *   <li>Set {@link Player#setPendingZoneId(int)} to the destination
 *       zone id.</li>
 *   <li>Emit the InfoResponse + Zoning1 close + Location sequence
 *       to mirror the retail flow above.</li>
 * </ol>
 *
 * <p>See memory: {@code genrep_teleport_full_sequence.md},
 * {@code iter45_misc_subtags.md} (clarifies 0x2f vs 0x4c).
 */
public class GenrepMapPick extends GamePacketDecoderUDP {

    public GenrepMapPick(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        // Sub-packet layout: 03 [seq:2] 1f [mapId:2] 2f [body 8B]
        // Skip the 8-byte header to land at body start.
        skip(8);
        int b0 = read(), b1 = read(), b2 = read(), b3 = read();
        int b4 = read(), b5 = read(), b6 = read(), b7 = read();
        boolean allFf =
            (b0 & b1 & b2 & b3 & b4 & b5 & b6 & b7) == 0xff;

        String body = String.format(
            "%02x %02x %02x %02x %02x %02x %02x %02x",
            b0, b1, b2, b3, b4, b5, b6, b7);
        Out.writeln(Out.Info,
            "GenrepMapPick: player=" + pl.getName()
            + " body=" + body
            + " all-FF-sentinel=" + allFf
            + " — (observation-only stub, #203 impl pending)");

        // TODO #203: trigger full GR teleport flow:
        //   1. Look up player's faction default GR or nearest active
        //   2. setPendingZoneId(destination)
        //   3. Emit S→C 0x03/0x23 InfoResponse + 0x22/0x03 close +
        //      TCP 0x83/0x0d Zoning2 + 0x83/0x0c Location
        //   4. Wait for C→S 0x03/0x2a RequestInitBurst then flush
        //      world state at the destination
    }
}
