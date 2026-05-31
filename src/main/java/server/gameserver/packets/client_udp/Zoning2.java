package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.internalEvents.DummyEvent;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Packet830D;
import server.gameserver.packets.server_udp.UDPAlive;
import server.tools.Out;
import server.tools.Timer;

/**
 * Client "ready to load the new zone" packet (reliable
 * {@code 0x03/0x22} sub {@code 0x03}). This is phase 2 of the
 * retail zone-cross handshake — see {@link Zoning1} for the full
 * flow.
 *
 * <h3>Retail response (decoded 2026-05-14, RETAIL_PLAZA_CROSSZONE,
 * 6 crossings)</h3>
 *
 * <pre>
 *   C-&gt;S  Zoning2  0x03/0x22/0x03
 *   S-&gt;C  TCP 0x83/0x0d GameinfoReady   (~190ms after Zoning2)
 *   S-&gt;C  TCP 0x83/0x0c Location(path)
 *   C-&gt;S  0x03/0x08 ReliableAck
 *   S-&gt;C  UDP 0x04 UDPAlive             (~15ms after Location)
 *   server UDP wrapper RESETS: counter-&gt;1, NEW sessionkey
 * </pre>
 *
 * <p>The session reset is the crucial step that was missing
 * before: the client restarts its reliable counter from 1 with a
 * new session key the moment the cross completes, and the server
 * MUST mirror that or the reliable layer desyncs and the client
 * hangs on the "Synchronizing" overlay. The reset is done here,
 * right before the UDPAlive is built, so the UDPAlive's
 * {@code -sessionkey} field carries the new key for the client to
 * adopt.
 */
public class Zoning2 extends GamePacketDecoderUDP {

    public Zoning2(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        // Commit the zone switch that Zoning1 only recorded as
        // pending. Doing it here (not in Zoning1) keeps the server
        // serving the source zone until the client is actually
        // ready to load the destination — see Zoning1 javadoc.
        int pending = pl.getPendingZoneId();
        if (pending != 0) {
            pl.getCharacter().setMisc(
                server.database.playerCharacters.PlayerCharacter
                    .MISC_LOCATION, pending);
            pl.updateZone();
            ((server.database.playerCharacters.inventory.PlayerInventory)
                pl.getCharacter().getContainer(
                    server.database.playerCharacters.PlayerCharacter
                        .PLAYERCONTAINER_F2)).doSort();
            pl.setPendingZoneId(0);
            Out.writeln(Out.Info,
                "Zoning2: committed zone switch to "
                + pending + " for "
                + (pl.getCharacter() == null ? "?"
                        : pl.getCharacter().getName()));
        }

        // TCP zone-swap pair: GameinfoReady then Location (carries
        // the destination BSP path resolved from the now-committed
        // MISC_LOCATION). spawnIdx now comes from the canonical
        // source: defs.worldinfo[destZone].f3, via
        // Zone.getDefaultSpawnIdx() → PortalResolver.lookupSpawnIdx().
        //
        // Live-verified DB values: plaza_p1=16, plaza_p3=0 (preserves
        // seam coords), plaza_p4=0, reaktor=1. Earlier today's
        // bsp-prefix heuristic was wrong because it returned 16 for
        // ALL plaza/ zones (regressed walk-cross spawn position).
        if (pl.getTcpConnection() != null) {
            pl.send(new Packet830D());
            server.gameserver.Zone destZone =
                server.gameserver.ZoneManager.getZone(pending);
            int spawnIdx = (destZone == null)
                ? 0 : destZone.getDefaultSpawnIdx();
            Out.writeln(Out.Info,
                "Zoning2: Location spawnIdx=" + spawnIdx
                + " (from client_defs.worldinfo[" + pending
                + "].f3) — 0 preserves seam coords, non-zero "
                + "teleports to appplaces entry");
            pl.send(new Location(pl, spawnIdx));
        } else {
            Out.writeln(Out.Warning,
                "Zoning2: no TCP connection for "
                + (pl.getCharacter() == null ? "?"
                        : pl.getCharacter().getName())
                + " — zone swap dropped");
            return;
        }
        // UDPAlive + UDP-session reset, ~20 ms later so the TCP
        // Location frame settles on the client first (matches the
        // ~15 ms retail gap).
        pl.addEvent(new Zoning2Answer());
    }

    /**
     * Delayed step: regenerate the UDP session (counter→0, fresh
     * session key, cleared retransmit ring) THEN emit the UDPAlive
     * carrying that new key. Order is mandatory — UDPAlive reads
     * the session key at construction time.
     *
     * <p><strong>Reliable window-init primer (root-cause fix,
     * 2026-05-30).</strong> After the counter reset, the very first
     * reliable {@code 0x13/0x03} packet on the fresh seq counter
     * MUST be the tiny standalone {@code sub-op 0x08}
     * ({@link server.gameserver.packets.server_udp.ZoningEnd})
     * carrying seq=1. Retail emits exactly this at every
     * UDP-session reset / reconnect (5/6 resets in
     * {@code RETAIL_PLAZA_CROSSZONE}, every reconnect in
     * {@code HANNIBAL}) — it is the signal that re-initialises the
     * client's reliable RECEIVE window to expect seq=1 and flush
     * the pre-reset NAK backlog. Without it, Ceres' first post-reset
     * reliable was a large CharInfo/heartbeat packet; the client
     * never cleanly re-based its window, kept NAKing stale seqs
     * (raw {@code 0x01} retransmit-requests), and the server
     * answered each with a {@code 0x02} retransmit — the NAK-storm /
     * "0x02 over-use" livelock documented in
     * {@code ceres-reliable-nak-storm-diff}. Emitting the 0x08
     * primer first claims seq=1 and lets the client re-sync without
     * a NAK storm, matching retail's monotonic, dup-free post-reset
     * stream.
     */
    static class Zoning2Answer extends DummyEvent {
        public Zoning2Answer() {
            eventTime = Timer.getRealtime() + 20;
        }

        @Override
        public void execute(Player pl) {
            if (pl == null || pl.getUdpConnection() == null) {
                return;
            }
            short newKey = pl.getUdpConnection()
                    .resetSessionForZoneCross();
            Out.writeln(Out.Info,
                "Zoning2: UDP session reset for "
                + (pl.getCharacter() == null ? "?"
                        : pl.getCharacter().getName())
                + " — counter=0 newSessionKey=0x"
                + Integer.toHexString(newKey & 0xFFFF));
            // Transport keepalive carrying the NEW session key, then
            // the reliable window-init primer (seq=1, sub-op 0x08).
            // Order matters: UDPAlive (raw 0x04, no seq) advertises the
            // key the client must adopt; the 0x08 ZoningEnd is the FIRST
            // reliable on the fresh counter so it lands on seq=1 and
            // re-bases the client's receive window (see class javadoc).
            pl.send(new UDPAlive(pl));
            pl.send(new server.gameserver.packets.server_udp
                    .ZoningEnd(pl));
        }
    }
}
