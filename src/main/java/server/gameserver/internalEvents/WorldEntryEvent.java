package server.gameserver.internalEvents;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.server_udp.CharInfo;
import server.gameserver.packets.server_udp.CharInfoV1;
import server.gameserver.packets.server_udp.ChatList;
import server.gameserver.packets.server_udp.InfoResponse;
import server.gameserver.packets.server_udp.LongPlayerInfo;
import server.gameserver.packets.server_udp.PlayerPositionUpdate;
import server.gameserver.packets.server_udp.PositionUpdate;
import server.gameserver.packets.server_udp.ShortPlayerInfo;
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
        // CharInfoV1 (0x22 0x01) sets sync bit 0.
        safeSend(pl, () -> new CharInfoV1(pl), "CharInfoV1 (sync bit 0)");

        // CharInfo / CharsysInfo (0x22 0x02 0x01) sets sync bit 1.
        // Fragmented via 0x13 → 0x03 → 0x07 multi-part wrapper.
        safeSend(pl, () -> new CharInfo(pl), "CharInfo / CharsysInfo (sync bit 1)");

        // ── Phase 2: InfoResponse + TimeSync (retail pkt #11) ────────
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
        safeSend(pl, () -> new ShortPlayerInfo(pl, pc, mapId), "ShortPlayerInfo (self)");
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

        // ── Start the S→C TimeSync heartbeat (0x13 → 0x03 → 0x1f) ──
        // Retail streams this at ~1 Hz throughout the session and the
        // modern client appears to gate its "SYNCHRONIZING INTO CITY
        // ZONE" clear on seeing the stream, not on any single packet in
        // the initial burst. See docs/retail_burst_analysis.md §5.
        pl.addEvent(new TimeSyncHeartbeatEvent());

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
