package server.gameserver.packets.client_udp;

import java.net.DatagramPacket;

import server.gameserver.Player;
import server.gameserver.internalEvents.DummyEvent;
import server.gameserver.internalEvents.WorldEntryEvent;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.UDPAlive;
import server.tools.Out;
import server.tools.Timer;

/**
 * Handles incoming UDP handshake (0x01) packets from the client.
 *
 * The modern NCE 2.5 client performs a 3-way UDP handshake immediately after
 * receiving the {@code UDPServerData} TCP reply. Each handshake packet is
 * obfuscated per-packet with an XOR cipher keyed off the first byte (see
 * {@link server.networktools.PacketObfuscator}). After the third handshake the
 * server must:
 * <ol>
 *   <li>Acknowledge the handshake with {@link UDPAlive}.</li>
 *   <li>Mark the player as logged in.</li>
 *   <li>Schedule a {@link WorldEntryEvent} to stream the full world state
 *       (CharInfo, UpdateModel, PositionUpdate, self LongPlayerInfo, NPCs,
 *       ZoningEnd, ...) so the client can leave the loading screen.</li>
 * </ol>
 */
public class HandshakeUDP extends GamePacketDecoderUDP {

    public HandshakeUDP(DatagramPacket dp) {
        super(dp);
    }

    public void execute(Player pl) {
        skip(9);
        pl.getUdpConnection().setInterfaceId(read());

        pl.addEvent(new HandshakeUDPAnswer());
    }

    class HandshakeUDPAnswer extends DummyEvent {
        public HandshakeUDPAnswer() {
            eventTime = Timer.getRealtime() + 100;
        }

        public void execute(Player pl) {
            pl.send(new UDPAlive(pl));
            if (!pl.getUdpConnection().getHandshakingState()) {
                pl.getUdpConnection().setHandshakingState(true);
                pl.addEvent(new HandshakeUDPAnswer2());
            }
        }
    }

    /**
     * Second phase of the handshake: acknowledge the final UDP packet and
     * kick off the world-entry stream. The world-entry stream is delayed by
     * a small amount via {@link WorldEntryEvent} so that the ack reaches the
     * client before the flood of 0x13 gamedata packets arrives.
     */
    class HandshakeUDPAnswer2 extends DummyEvent {
        public HandshakeUDPAnswer2() {
            eventTime = Timer.getRealtime();
        }

        public void execute(Player pl) {
            pl.getUdpConnection().setHandshakingState(false);
            pl.setloggedin();
            Out.writeln(Out.Info, "HandshakeUDPAnswer2: player logged in, scheduling world entry");

            // Ack the final handshake first so the client stops retransmitting.
            try {
                pl.send(new UDPAlive(pl));
            } catch (Exception e) {
                Out.writeln(Out.Error, "UDPAlive (final handshake ack) failed: " + e.getMessage());
            }

            // Stream the full world-entry burst shortly after; this replaces
            // the old scattered set of individual sends (UpdateModel / CharInfo
            // / TimeSync / PositionUpdate) and adds the additional packets
            // required by the modern client (self LongPlayerInfo,
            // ShortPlayerInfo, PlayerPositionUpdate, weather, NPCs, ZoningEnd).
            pl.addEvent(new WorldEntryEvent());
        }
    }
}
