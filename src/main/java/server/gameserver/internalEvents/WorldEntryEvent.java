package server.gameserver.internalEvents;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.server_udp.CharInfo;
import server.gameserver.packets.server_udp.LongPlayerInfo;
import server.gameserver.packets.server_udp.PlayerPositionUpdate;
import server.gameserver.packets.server_udp.PositionUpdate;
import server.gameserver.packets.server_udp.ShortPlayerInfo;
import server.gameserver.packets.server_udp.TimeSync;
import server.gameserver.packets.server_udp.UDPAlive;
import server.gameserver.packets.server_udp.UpdateModel;
import server.gameserver.packets.server_udp.WorldWeather;
import server.gameserver.packets.server_udp.ZoningEnd;
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

        // Keepalive to confirm the session is still live before flooding the
        // client with state.
        safeSend(pl, () -> new UDPAlive(pl), "UDPAlive (pre-stream)");

        // Model / appearance for the player character. Must be sent before the
        // bulk CharInfo packet because the client references the model when
        // parsing the inventory section.
        safeSend(pl, () -> new UpdateModel(pl), "UpdateModel");

        // Core CharInfo: stats, skills, faction sympathies, inventory, QB,
        // GoGo, money, faction, epics. This is the biggest packet in the
        // sequence (multi-part 0x13 -> 0x03 -> 0x07 chained). Retail fragments
        // this across ~8 UDP datagrams.
        safeSend(pl, () -> new CharInfo(pl), "CharInfo");

        // Initial server time. Sent with clienttime=0 because the client has
        // not sent a GetTimeSync yet; it will request an authoritative time
        // sync once it processes this packet.
        safeSend(pl, () -> new TimeSync(pl, 0), "TimeSync");

        // StartPosition: tells the client where to place the character in the
        // world. Wrapped as REL_START_POS (0x2c).
        safeSend(pl, () -> new PositionUpdate(pl), "PositionUpdate (start pos)");

        // Weather: retail sends this during world entry even for zones with
        // indoor-only layouts. Default to clear weather.
        safeSend(pl, () -> new WorldWeather(pl), "WorldWeather");

        // The player's own long/short info. The client keeps a separate zone
        // registry keyed by mapId; it expects to see itself in that registry
        // before the ZoningEnd terminator fires.
        safeSend(pl, () -> new LongPlayerInfo(pl, pc, mapId), "LongPlayerInfo (self)");
        safeSend(pl, () -> new ShortPlayerInfo(pl, pc, mapId), "ShortPlayerInfo (self)");
        safeSend(pl, () -> new PlayerPositionUpdate(pl, pc, mapId), "PlayerPositionUpdate (self)");

        // Other players already in the zone.
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
            // Announce the new player to everybody else already in the zone.
            try {
                zone.sendnewPlayerinZone(pl);
            } catch (Exception e) {
                Out.writeln(Out.Error, "WorldEntryEvent: sendnewPlayerinZone failed: " + e.getMessage());
            }
        }

        // Terminator: ZoningEnd tells the client we're done streaming the
        // world-entry burst. Without this the client stays on the loading
        // screen until its socket times out.
        safeSend(pl, () -> new ZoningEnd(pl), "ZoningEnd");

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
