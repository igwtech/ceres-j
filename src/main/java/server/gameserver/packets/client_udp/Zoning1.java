package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.inventory.PlayerInventory;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.tools.Out;

/**
 * Client zone-edge crossing <em>notification</em> (reliable
 * {@code 0x03/0x22} sub {@code 0x0d}). The modern NCE 2.5.x client
 * fires this when the player walks into a BSP zone-trigger volume;
 * the destination zone_id is in the body.
 *
 * <h3>Retail flow (decoded 2026-05-14 from
 * {@code RETAIL_PLAZA_CROSSZONE} pcap, 6 crossings)</h3>
 *
 * <pre>
 *   C-&gt;S  Zoning1  0x03/0x22/0x0d  [target zone_id LE32]
 *         (server sends NO reply to Zoning1)
 *   ~500ms  client preloads the destination BSP on its own
 *   C-&gt;S  Zoning2  0x03/0x22/0x03
 *   S-&gt;C  TCP 0x83/0x0c Location(dest path)
 *   C-&gt;S  0x03/0x08 ReliableAck
 *   S-&gt;C  UDP 0x04 UDPAlive  (+~15ms)
 *   server UDP wrapper resets: counter-&gt;1, new sessionkey
 * </pre>
 *
 * <p><strong>Zoning1 itself gets no zone-cross response.</strong>
 * An earlier implementation answered Zoning1 immediately with
 * {@code Packet830D + Location} (+ an InteractionAck/UDPAlive
 * burst). That premature Location derailed the client's state
 * machine: it never emitted Zoning2 and retransmitted Zoning1
 * every ~2 s until the TCP connection timed out — the
 * "Synchronizing" overlay → Neocron-splash → hard hang seen in
 * live testing on plaza_p1 → plaza_p3. The whole zone-swap
 * response now lives in {@link Zoning2}, which is where retail
 * sends it.
 *
 * <p>The reliable {@code 0x03} sub-packet is still ACKed
 * automatically by {@code GamePacketReaderUDP.readPacket} — no
 * explicit ack is needed here.
 *
 * <p>See {@code zone_cross_2phase_handshake} memory + the
 * {@code RETAIL_PLAZA_CROSSZONE} capture for byte-level evidence.
 */
public class Zoning1 extends GamePacketDecoderUDP {

    public Zoning1(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        // Body layout from live capture: skip 14 bytes of header
        // (with the 03/seq reliable prefix the zone_id lands at
        // offset 14), then [location LE32][legacy session id LE32].
        skip(14);
        int newLocation = readInt();
        readInt(); // legacy SZoning1 session id, unused

        int oldLocation =
            pl.getCharacter().getMisc(PlayerCharacter.MISC_LOCATION);
        server.gameserver.Zone resolvedZone =
            server.gameserver.ZoneManager.getZone(newLocation);
        Out.writeln(Out.Info,
            "Zoning1: target zone_id=" + newLocation
            + " (from=" + oldLocation + ")"
            + " resolved bsp='" + (resolvedZone == null ? "<null>"
                : resolvedZone.getWorldname()) + "'");

        // Server-side bookkeeping only. The destination BSP path is
        // delivered in response to Zoning2 (see class javadoc).
        pl.getCharacter().setMisc(PlayerCharacter.MISC_LOCATION,
                newLocation);
        // Do NOT zero X/Y/Z here — keep prior-zone coords until the
        // next Movement overwrites them. Zeroing broke the reconnect
        // spawn (see git history: revert 396829d).
        pl.updateZone();
        ((PlayerInventory) pl.getCharacter().getContainer(
                PlayerCharacter.PLAYERCONTAINER_F2)).doSort();

        // No packet emission. Retail sends nothing in reply to
        // Zoning1; the client drives itself to Zoning2 after it has
        // preloaded the destination BSP (~500 ms).
    }
}
