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
        PlayerCharacter pcAtCross = pl.getCharacter();
        Out.writeln(Out.Info,
            "Zoning1: target zone_id=" + newLocation
            + " (from=" + oldLocation + ")"
            + " resolved bsp='" + (resolvedZone == null ? "<null>"
                : resolvedZone.getWorldname()) + "'"
            // Instrumentation for the sector-seam entry-point RE:
            // the player's coords at the moment of crossing tell us
            // which edge was crossed and validate the TinNS mirror
            // constants against live plaza data.
            + " posX=" + pcAtCross.getMisc(PlayerCharacter.MISC_X_COORDINATE)
            + " posY=" + pcAtCross.getMisc(PlayerCharacter.MISC_Y_COORDINATE)
            + " posZ=" + pcAtCross.getMisc(PlayerCharacter.MISC_Z_COORDINATE));

        // Record the destination; the actual commit happens in
        // SZoning1ConfirmEvent (~450 ms later, right after the
        // confirm is sent and just before the client reconnects to
        // the destination worldserver). Committing in this handler
        // would move the player server-side before the confirm,
        // streaming destination NPC/state to a client that hasn't
        // loaded the new BSP. See SZoning1ConfirmEvent javadoc for
        // why the commit is no longer in Zoning2 (the client never
        // sends a UDP Zoning2 — it reconnects instead).
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
        //
        // TIMING: retail sends this confirm ~450 ms after Zoning1
        // (consistent across all 8 RETAIL_PLAZA_CROSSZONE
        // crossings), not immediately. Sending it at +4 ms — while
        // the client is still mid-trigger and has not yet frozen
        // its simulation for the transition — the client keeps
        // streaming position and never enters the
        // preload→Zoning2 state. Defer the confirm by ~450 ms via
        // a delayed event so it lands when the client is actually
        // waiting for it.
        pl.addEvent(new SZoning1ConfirmEvent(szoningId,
                resolvedZone));
    }

    /**
     * Fires the SZoning1 confirm ~450 ms after Zoning1, matching
     * the retail Zoning1→confirm gap, THEN commits the zone switch.
     *
     * <p><strong>Why the commit moved here from {@link Zoning2}.</strong>
     * Live pcap + server-log evidence (2026-05-16): the modern
     * client does <em>not</em> send a UDP {@code 0x22/0x03}
     * Zoning2. After it receives this confirm and the worldserver
     * handoff ({@code WorldInfoSrv} 0x19/0x04) it tears down the
     * session and <em>reconnects</em> (new TCP login + new UDP
     * socket) to the destination worldserver. {@code Zoning2}
     * never executes, so the commit that lived there never ran:
     * the reconnected session re-read the character's
     * {@code MISC_LOCATION} (still the source zone) and streamed
     * the wrong zone's world-state, leaving the client loaded into
     * the destination BSP while the server simulated the source —
     * the player could not move ("frozen until logout").
     *
     * <p>{@code PlayerCharacterManager.getCharacter()} returns the
     * cached in-memory {@code PlayerCharacter}, shared across the
     * reconnect, so persisting {@code MISC_LOCATION} here (before
     * the client reconnects) makes the reconnected
     * {@code WorldEntryEvent} resolve the destination zone. The
     * old-session {@code updateZone()} is safe now that the
     * reliable layer is healthy (the old socket is abandoned a
     * moment later anyway).
     */
    static class SZoning1ConfirmEvent
            extends server.gameserver.internalEvents.DummyEvent {
        private final int szoningId;
        private final server.gameserver.Zone destZone;

        SZoning1ConfirmEvent(int szoningId,
                             server.gameserver.Zone destZone) {
            this.szoningId = szoningId;
            this.destZone = destZone;
            this.eventTime =
                    server.tools.Timer.getRealtime() + 450;
        }

        @Override
        public void execute(Player pl) {
            if (pl == null || pl.getUdpConnection() == null) {
                return;
            }
            pl.send(new server.gameserver.packets.server_udp
                    .SZoning1(szoningId, pl, destZone));

            // Commit the zone switch the client only recorded as
            // pending in Zoning1. Must happen before the client
            // reconnects to the destination worldserver (it does
            // so right after this confirm — there is no Zoning2),
            // so the reconnected session's WorldEntry resolves the
            // destination zone from the shared in-memory
            // PlayerCharacter.
            int pending = pl.getPendingZoneId();
            if (pending != 0 && pl.getCharacter() != null) {
                server.database.playerCharacters.PlayerCharacter pc =
                        pl.getCharacter();
                pc.setMisc(server.database.playerCharacters
                        .PlayerCharacter.MISC_LOCATION, pending);

                // Sector-seam entry point — APPLIED for WASTELAND
                // OUTDOOR zones only (destination worldId >= 2001,
                // the TinNS GetVhcZoningDestination grid). Live
                // long-route data (2026-05-16, plaza→pepper→
                // industry→outzone→terrain a_07/b_07/c_07) confirms:
                // outdoor terrain (>=2001) is crossed BEYOND the
                // ±OUT limit (posY -30835/-24591…) so the TinNS
                // mirror snaps the crossed axis to the opposite
                // IN-limit correctly; indexed CITY zones (<2001:
                // plaza/pepper/industry/outzone p-sectors) are
                // crossed WITHIN ±bounds and use a different,
                // still-unknown mechanism — applying the mirror
                // there mis-places the player (regressed before),
                // so it is gated off for them. The worldId>=2001
                // split is the TinNS-documented outdoor scope
                // (mOutdoorBaseWorldId), now empirically validated.
                int cx = pc.getMisc(server.database.playerCharacters
                        .PlayerCharacter.MISC_X_COORDINATE);
                int cy = pc.getMisc(server.database.playerCharacters
                        .PlayerCharacter.MISC_Y_COORDINATE);
                int cz = pc.getMisc(server.database.playerCharacters
                        .PlayerCharacter.MISC_Z_COORDINATE);
                int[] entry = server.gameserver.ZoneBoundaries
                        .mirrorEntryPosition(cx, cy, cz);
                if (entry != null
                        && server.gameserver.ZoneBoundaries
                            .isWastelandOutdoor(pending)) {
                    pc.setMisc(server.database.playerCharacters
                        .PlayerCharacter.MISC_X_COORDINATE, entry[0]);
                    pc.setMisc(server.database.playerCharacters
                        .PlayerCharacter.MISC_Y_COORDINATE, entry[1]);
                    pc.setMisc(server.database.playerCharacters
                        .PlayerCharacter.MISC_Z_COORDINATE, entry[2]);
                }

                // City-sector walk-cross: retail sends NO server self-
                // position (RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT pcap;
                // zone_portal_params.md §7). Mark this so the cross-
                // reconnect's world-state burst suppresses its self-
                // PlayerPositionUpdate and the client self-positions from
                // local .dat geometry — instead of Ceres pushing stale
                // source-zone coords (the task #174 "reset to map
                // centre"). Wasteland/outdoor (>=2001) is unaffected: it
                // still uses the coord mirror above.
                boolean cityCross = server.gameserver.ZoneBoundaries
                        .isIndexedCitySector(pending);
                pl.setPendingCityCrossSelfPosSuppress(cityCross);

                pl.updateZone();
                pl.setPendingZoneId(0);
                Out.writeln(Out.Info,
                    "SZoning1Confirm: committed zone switch to "
                    + pending + " for " + pc.getName()
                    + " srcPos=" + cx + "," + cy + "," + cz
                    + " mirrorWouldBe=" + (entry == null ? "(no-op)"
                        : entry[0] + "," + entry[1] + "," + entry[2])
                    + (cityCross
                        ? " [CITY cross — self-pos push SUPPRESSED,"
                          + " client self-positions (retail-faithful)]"
                        : " [outdoor/other — default path]"));
            }
        }
    }
}
