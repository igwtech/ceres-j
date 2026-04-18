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
            // Guard: WorldEntryEvent must fire EXACTLY ONCE per session.
            // Re-sending CharInfo multipart resets the client's state
            // machine from state 4 (in-world) back to state 1→2
            // (joining), triggering a 15-second "Connecting to
            // worldserver failed" timeout in FUN_0055bdc0 case 2.
            //
            // The NC2 client performs a zone-handoff ~10 s after the
            // initial login: it closes its UDP socket and opens a new
            // one from a different port, which produces a second
            // HandshakeUDP sequence. We must NOT re-stream world state
            // on this reconnect — just ack the handshake.
            long now = Timer.getRealtime();
            if (now - pl.getLastWorldEntryAt() < 500) {
                // Duplicate within 500 ms — interleaved handshake events
                pl.getUdpConnection().setHandshakingState(false);
                return;
            }

            boolean firstEntry = !pl.isloggedin();
            pl.setLastWorldEntryAt(now);
            pl.getUdpConnection().setHandshakingState(false);
            pl.setloggedin();

            // Ack the handshake so the client stops retransmitting.
            try {
                pl.send(new UDPAlive(pl));
            } catch (Exception e) {
                Out.writeln(Out.Error, "UDPAlive (handshake ack) failed: " + e.getMessage());
            }

            if (firstEntry) {
                Out.writeln(Out.Info, "HandshakeUDPAnswer2: first login, scheduling world entry");
                pl.addEvent(new WorldEntryEvent());
            } else {
                // Zone-handoff reconnect: the client is already in
                // state 4 (in-world). Re-sending CharInfo multipart
                // would reset its state machine to 1→2 (joining) and
                // trigger a 15s timeout. Retail doesn't have zone-
                // handoff at all. Just ack — session is established.
                Out.writeln(Out.Info, "HandshakeUDPAnswer2: zone-handoff reconnect, skipping WorldEntryEvent");
            }
        }
    }
}
