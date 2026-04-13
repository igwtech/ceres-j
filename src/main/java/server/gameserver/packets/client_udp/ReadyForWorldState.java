package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.internalEvents.DummyEvent;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.ChatList;
import server.gameserver.packets.server_udp.GamePacketTimeSync;
import server.gameserver.packets.server_udp.InfoResponse;
import server.gameserver.packets.server_udp.LongPlayerInfo;
import server.gameserver.packets.server_udp.PlayerPositionUpdate;
import server.tools.Out;
import server.tools.Timer;

/**
 * Handles the client's {@code 0x13 -> 0x03 -> 0x24} "ready for world state"
 * trigger.
 *
 * <p>All four retail captures (ACC1/ACC2 × CHAR1/CHAR2) show the client
 * sending a single 6-byte reliable {@code 0x03 -> 0x24} packet with a
 * 2-byte inner payload ({@code 01 00} or {@code 02 00}) shortly after
 * receiving the initial world-entry burst. Retail servers respond within
 * microseconds with a large burst containing:
 * <ul>
 *   <li>{@code 0x03 -> 0x33} ChatList</li>
 *   <li>{@code 0x03 -> 0x23} InfoResponse (session variant)</li>
 *   <li>{@code 0x03 -> 0x1f} GamePackets / TimeSync</li>
 *   <li><b>20-25 x {@code 0x03 -> 0x1b}</b> position updates for zone
 *       objects / players (the dominant traffic in the response)</li>
 *   <li>{@code 0x03 -> 0x2d} NPCData</li>
 *   <li>{@code 0x03 -> 0x25} PlayerInfo for self</li>
 *   <li>Additional raw {@code 0x1b} position broadcasts</li>
 * </ul>
 *
 * <p>Without this response the modern client remains stuck on the
 * "SYNCHRONIZING INTO CITY ZONE" overlay and aborts after ~26 seconds.
 * This handler replays the zone-population subset of {@link
 * server.gameserver.internalEvents.WorldEntryEvent} — specifically the
 * packets that show up in the retail response — and lets the client
 * proceed past sync state.
 */
public class ReadyForWorldState extends GamePacketDecoderUDP {

    public ReadyForWorldState(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || pl.getCharacter() == null) {
            return;
        }
        Out.writeln(Out.Info, "ReadyForWorldState: client ready, streaming zone population for "
                + pl.getCharacter().getName());

        // Defer the response burst by a tick so the incoming packet's ACK
        // has a chance to leave the socket before we start flooding. The
        // retail server responds within ~1 ms but the event dispatcher
        // here is coarse — 20 ms is indistinguishable on the wire.
        pl.addEvent(new WorldStatePopulateEvent());
    }

    /**
     * Replays the zone-population portion of the world-entry burst: the
     * exact sub-types retail sends in response to 0x03→0x24.
     */
    static class WorldStatePopulateEvent extends DummyEvent {
        WorldStatePopulateEvent() {
            eventTime = Timer.getRealtime() + 20;
        }

        @Override
        public void execute(Player pl) {
            if (pl == null || pl.getCharacter() == null) {
                return;
            }
            PlayerCharacter pc = pl.getCharacter();
            int mapId = pl.getMapID();

            // 1. ChatList (0x03→0x33).
            trySend(pl, () -> new ChatList(pl), "ChatList");

            // 2. InfoResponse session variant (0x03→0x23).
            trySend(pl, () -> InfoResponse.sessionInfo(pl), "InfoResponse(session)");

            // 3. GamePackets TimeSync (0x03→0x1f).
            trySend(pl, () -> new GamePacketTimeSync(pl), "GamePacketTimeSync");

            // 4. Self PlayerInfo (0x03→0x25).
            trySend(pl, () -> new LongPlayerInfo(pl, pc, mapId), "LongPlayerInfo(self)");

            // 5. Self PlayerPositionUpdate (0x03→0x1b). Retail sends
            //    this one plus many more for each zone-resident object.
            trySend(pl, () -> new PlayerPositionUpdate(pl, pc, mapId),
                    "PlayerPositionUpdate(self)");

            // 6. Zone population — NPCs then other players. These end up
            //    as a stream of 0x03→0x2d NPCData and 0x03→0x1b position
            //    update packets, matching the retail response shape.
            Zone zone = pl.getZone();
            if (zone != null) {
                try {
                    zone.sendPlayersinZone(pl);
                } catch (Exception e) {
                    Out.writeln(Out.Error,
                            "WorldStatePopulate: sendPlayersinZone: " + e.getMessage());
                }
                try {
                    zone.sendNPCsinZone(pl);
                } catch (Exception e) {
                    Out.writeln(Out.Error,
                            "WorldStatePopulate: sendNPCsinZone: " + e.getMessage());
                }
            }

            // 7. One more GamePackets TimeSync to bracket the burst, as
            //    retail does.
            trySend(pl, () -> new GamePacketTimeSync(pl), "GamePacketTimeSync(close)");
        }
    }

    @FunctionalInterface
    private interface PacketFactory {
        server.interfaces.ServerUDPPacket build();
    }

    private static void trySend(Player pl, PacketFactory f, String label) {
        try {
            pl.send(f.build());
        } catch (Exception e) {
            Out.writeln(Out.Error, "WorldStatePopulate: " + label + ": " + e.getMessage());
        }
    }
}
