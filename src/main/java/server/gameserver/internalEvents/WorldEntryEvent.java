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

        // ── Heartbeats: DEFERRED until after zone-handoff ──
        // The client closes its UDP socket and opens a new one during
        // BSP load (~11 s after WorldEntryEvent). Any reliable packets
        // sent during this window are received on the OLD socket which
        // the client has already closed — they're permanently lost.
        // The session counter advances past what the client received,
        // creating a gap in the out-of-order list. After 10 s the
        // client reports "GAMENETMGR [CheckOOOList]: out-of-sync.
        // Msg num 19 more than 10000ms behind. Disconnecting."
        //
        // Heartbeats are started from PlayerUdpListener when the
        // zone-handoff completes (first gamedata from the new port).
        // See PlayerUdpListener.handle() zoneHandoffActive logic.

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
