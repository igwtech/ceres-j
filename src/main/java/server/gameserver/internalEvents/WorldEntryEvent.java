package server.gameserver.internalEvents;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.server_udp.CharInfo;
import server.gameserver.packets.server_udp.CharInfoV1;
import server.gameserver.packets.server_udp.ChatList;
import server.gameserver.packets.server_udp.InfoResponse;
import server.gameserver.packets.server_udp.InitInfoResponse02;
import server.gameserver.packets.server_udp.InitSoullight02;
import server.gameserver.packets.server_udp.InitUpdateModel02;
import server.gameserver.packets.server_udp.InitWeather02;
import server.gameserver.packets.server_udp.LongPlayerInfo;
import server.gameserver.packets.server_udp.PlayerPositionUpdate;
import server.gameserver.packets.server_udp.PositionUpdate;
import server.gameserver.packets.server_udp.TimeSync;
import server.gameserver.packets.server_udp.UDPAlive;
import server.gameserver.packets.server_udp.UpdateModel;
import server.gameserver.packets.server_udp.WorldWeather;
import server.tools.Out;
import server.tools.Timer;

/**
 * Streams the full world-entry packet sequence to a freshly-logged-in player.
 *
 * The modern NCE 2.5 client times out on a loading screen if it does not
 * receive the full post-handshake burst: character data, model, position,
 * world weather, other players, NPCs and finally a ZoningEnd terminator.
 *
 * Retail server behaviour observed in pcaps (zone pepper/pepper_p3):
 * <ul>
 *   <li>Immediately after the UDP handshake completes, retail sends ~15
 *       large (~440 byte) 0x13 packets containing CharInfo, UpdateModel,
 *       PositionUpdate, LongPlayerInfo for self, ShortPlayerInfo for self,
 *       weather, zone NPCs, and a ZoningEnd terminator.</li>
 *   <li>After a short delay the client sends back an acknowledgment sub-packet
 *       and the server follows up with further zone state (other players,
 *       items).</li>
 * </ul>
 *
 * This event serialises the above burst. It's scheduled from
 * {@code HandshakeUDP} once the 3-way UDP handshake finishes, and it drains
 * all required packets into the player's UDP socket in the order the client
 * expects.
 */
public class WorldEntryEvent extends DummyEvent {

    /** Delay (ms) from the final UDP handshake ack before streaming begins. */
    public static final long START_DELAY_MS = 50;

    public WorldEntryEvent() {
        eventTime = Timer.getRealtime() + START_DELAY_MS;
    }

    public WorldEntryEvent(long delayMs) {
        eventTime = Timer.getRealtime() + delayMs;
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || pl.getCharacter() == null) {
            Out.writeln(Out.Error, "WorldEntryEvent: player or character missing, aborting");
            return;
        }
        PlayerCharacter pc = pl.getCharacter();
        int mapId = pl.getMapID();

        Out.writeln(Out.Info, "WorldEntryEvent: streaming world state for "
                + pc.getName() + " mapId=" + mapId);

        // ── Keepalive ─────────────────────────────────────────────────
        safeSend(pl, () -> new UDPAlive(pl), "UDPAlive (pre-stream)");

        // ── Phase 1: CharInfo multipart (retail sends this first) ────
        // Retail sends ONE multipart stream: 0x22 0x02 0x01 (CharsysInfo)
        // with per-fragment header discriminator=0x01. FUN_0055c270
        // reads the discriminator to set bit 0 (CharInfo received) and
        // the payload byte[1]=0x02 to set bit 1 (CharsysInfo received).
        //
        // REMOVED: CharInfoV1 as a separate multipart. Sending two
        // multipart objects with the same chain_key=0x00 corrupts the
        // reassembly — the client sees total_size=72 from CharInfoV1's
        // header and truncates the CharInfo data that follows. Retail
        // never sends a separate 0x22 0x01 payload.
        safeSend(pl, () -> new CharInfo(pl), "CharInfo / CharsysInfo (bits 0+1)");

        // ── Phase 2: 0x02 wrapper initialization packets ────────────
        // Retail sends these via the 0x02 "simplified reliable" wrapper
        // right after CharInfo multipart. They carry initialization
        // data the client needs: session flags, weather/time of day,
        // player model, and Soullight value. Without them the client
        // may not fully initialize its core data structures.
        safeSend(pl, () -> new InitInfoResponse02(pl), "0x02 InfoResponse (session flags)");
        safeSend(pl, () -> new InitWeather02(pl), "0x02 Weather (time of day)");
        safeSend(pl, () -> new InitUpdateModel02(pl), "0x02 UpdateModel (appearance)");
        safeSend(pl, () -> new InitSoullight02(pl), "0x02 Soullight (soul energy=100.0)");

        // ── Phase 3: InfoResponse + TimeSync (retail pkt #11) ────────
        // InfoResponse zone variant (0x03→0x23): 20 00 10 00 00 00
        // Observed in retail right after CharInfo multipart completes.
        safeSend(pl, () -> InfoResponse.zoneInfo(pl), "InfoResponse (zone info)");

        // TimeSync — server time baseline.
        safeSend(pl, () -> new TimeSync(pl, 0), "TimeSync");

        // ── Phase 3: ChatList + InfoResponse + zone data (retail pkt #12) ──
        // ChatList (0x03→0x33): ff 00 — chat channel list.
        safeSend(pl, () -> new ChatList(pl), "ChatList");

        // InfoResponse session variant (0x03→0x23): 0e 00 ... 01 00
        safeSend(pl, () -> InfoResponse.sessionInfo(pl), "InfoResponse (session info)");

        // Player position / zone entry data.
        safeSend(pl, () -> new PositionUpdate(pl), "PositionUpdate (start pos)");
        safeSend(pl, () -> new WorldWeather(pl), "WorldWeather");

        // The player's own long/short info + position.
        safeSend(pl, () -> new LongPlayerInfo(pl, pc, mapId), "LongPlayerInfo (self)");
        safeSend(pl, () -> new PlayerPositionUpdate(pl, pc, mapId), "PlayerPositionUpdate (self)");

        // ── Phase 4: UpdateModel (retail sends via 0x02 wrapper, we use 0x03) ──
        safeSend(pl, () -> new UpdateModel(pl), "UpdateModel");

        // ── Phase 5: Zone population (other players, NPCs) ──────────
        Zone zone = pl.getZone();
        if (zone != null) {
            try {
                zone.sendPlayersinZone(pl);
            } catch (Exception e) {
                Out.writeln(Out.Error, "WorldEntryEvent: sendPlayersinZone failed: " + e.getMessage());
            }
            try {
                zone.sendNPCsinZone(pl);
            } catch (Exception e) {
                Out.writeln(Out.Error, "WorldEntryEvent: sendNPCsinZone failed: " + e.getMessage());
            }
            try {
                zone.sendnewPlayerinZone(pl);
            } catch (Exception e) {
                Out.writeln(Out.Error, "WorldEntryEvent: sendnewPlayerinZone failed: " + e.getMessage());
            }
        }

        // ── NO ZoningEnd ────────────────────────────────────────────
        // Retail does NOT send ZoningEnd (0x03→0x08) during login.
        // Our previous ZoningEnd may have been confusing the client's
        // state machine. Removed to match retail behavior.

        // ── Heartbeats: start NOW on first login ──────────────────
        // Original design deferred heartbeats until after a zone-
        // handoff (HandshakeUDP starts them when the client closes
        // its UDP socket and reopens from a fresh ephemeral port,
        // typically ~11 s into the session). The intent was to
        // avoid losing reliable packets to a soon-to-close socket.
        //
        // But not every session triggers a zone-handoff — the user-
        // reported 2026-05-09 SYNCHRONIZING-overlay hang showed a
        // session where the client never closed its login socket,
        // so heartbeats never started, and TimeSync (which advances
        // the client's state-machine 3/6 → 4 per
        // FUN_0055b6f0 case 3) never streamed.
        //
        // Starting heartbeats NOW is safe: PlayerUdpListener's
        // rebindClient() redirects future packets when the client
        // does open a new socket, so at most a small handful of
        // heartbeats are lost during the close-and-reopen window —
        // and self-rescheduling means the client picks back up
        // automatically once rebound. ReliableTimeSyncRequest (the
        // explicit 0x03/0x0d C→S request) was the only thing
        // keeping previous sessions alive at all.
        pl.addEvent(new TimeSyncHeartbeatEvent());
        pl.addEvent(new PoolStatusHeartbeat());
        pl.addEvent(new ZoneStateHeartbeat());
        // ── UDPAlive keepalive (0x04) every ~3 s ──
        // Retail emits 8 UDPAlives per HANNIBAL session: 4 in the
        // handshake-reply burst + 4 periodic at ~3 s spacing.
        // Without the periodic ones the client's UDP-keepalive
        // expectation drifts and the harness's spare-UDPAlive
        // skip predicate (PcapReplayTest.isSpareUDPAlive) had to
        // be added to absorb the gap. See task #158.
        pl.addEvent(new UDPAliveHeartbeat());

        // ── TCP keepalive (0x83 0x8f) every ~10 s ──
        // Retail sends this on the TCP connection for the entire
        // session. Without it the client's TCP layer may time out.
        pl.addEvent(new TcpKeepaliveEvent());

        // ── Resource probe (one-shot RE harness, disabled) ──
        // ResourceProbeEvent cycles HP/PSI/STA/Soullight/Cash through
        // known distinct values to correlate server send-order with HUD
        // response. Test on 2026-04-25 confirmed PoolUpdate / PoolStatus /
        // SoullightUpdate / CashUpdateProbe(sub=0x04) DO NOT move the
        // self-HUD — those packets target foreign entities only. The
        // probe code is kept in the tree (commented out here) so it can
        // be re-enabled to test future candidate sub-opcodes.
        // Disabled: ResourceProbeEvent — see PROTOCOL.md "ResourceProbe test".
        // pl.addEvent(new ResourceProbeEvent());

        // Mark the player as waiting for a UDP zone-handoff handshake.
        // Once the client finishes loading the zone descriptor it closes
        // the login UDP socket and opens a fresh one from a new ephemeral
        // port. ListenerUDP uses this flag (plus source IP match) to pair
        // the incoming handshake with the right player, which is the only
        // disambiguation we have for multi-boxed clients on the same IP.
        pl.markHandoffPending();

        Out.writeln(Out.Info, "WorldEntryEvent: completed for " + pc.getName());
    }

    /** Functional helper so each packet instantiation is isolated from errors. */
    @FunctionalInterface
    private interface PacketFactory {
        server.interfaces.ServerUDPPacket build();
    }

    private void safeSend(Player pl, PacketFactory factory, String label) {
        try {
            pl.send(factory.build());
        } catch (Exception e) {
            Out.writeln(Out.Error, "WorldEntryEvent: " + label + " failed: " + e.getMessage());
        }
    }
}
