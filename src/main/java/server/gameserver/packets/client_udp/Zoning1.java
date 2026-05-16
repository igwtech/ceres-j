package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
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
        int szoningId = readInt(); // 2nd int → SZoning1's 0x23 [i] field

        int oldLocation =
            pl.getCharacter().getMisc(PlayerCharacter.MISC_LOCATION);
        server.gameserver.Zone resolvedZone =
            server.gameserver.ZoneManager.getZone(newLocation);
        Out.writeln(Out.Info,
            "Zoning1: target zone_id=" + newLocation
            + " (from=" + oldLocation + ")"
            + " resolved bsp='" + (resolvedZone == null ? "<null>"
                : resolvedZone.getWorldname()) + "'");

        // Record the destination but do NOT commit the zone switch
        // yet. Committing here (setMisc(MISC_LOCATION)+updateZone())
        // moves the player into the destination Zone server-side
        // immediately, so the heartbeats (ZoneStateHeartbeat etc.)
        // start streaming plaza_p3 NPC/state to a client that is
        // still in plaza_p1 and hasn't loaded the new BSP. That
        // wedges the client's zone-cross state machine — it never
        // emits Zoning2 and retransmits Zoning1 forever. Retail
        // keeps serving the source zone until Zoning2. The commit
        // happens in the Zoning2 handler, which reads this field.
        pl.setPendingZoneId(newLocation);

        // Send the SZoning1 confirmation — the server's "request
        // validated, proceed" reply. Retail ALWAYS sends this and
        // the client emits Zoning2 ~35 ms after receiving it; with
        // it absent the client never advances and reverts to the
        // source zone. SZoning1 builds the retail-exact pair
        //   0x03/0x1f [mapID] 25 13 [txn] 0e 02   (confirm marker)
        //   0x03/0x23 04 …      [i] [txn] 00 00   (zone-info ack)
        // verified byte-for-byte against RETAIL_PLAZA_CROSSZONE
        // (crossing D: 1f 05 00 25 13 02 30 0e 02 + 23 04 …
        // 01000000 0230 0000, i == the 2nd int of the Zoning1
        // body). Commit 2267ab6 removed this on the mistaken
        // theory that it caused the "Synchronizing" overlay; the
        // overlay was actually the reliable-layer bugs since
        // fixed (0x03/0x09 storm, spurious S→C 0x01, seq gaps,
        // mid-cross heartbeat flood). Restored now that the wire
        // is clean. The zone switch itself still commits in
        // Zoning2 (Location + UDPAlive + UDP-session reset).
        pl.send(new server.gameserver.packets.server_udp
                .SZoning1(szoningId, pl, resolvedZone));
    }
}
