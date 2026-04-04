package server.gameserver.packets.client_udp;

import java.net.DatagramPacket;

import server.gameserver.Player;
import server.gameserver.internalEvents.DummyEvent;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.UDPAlive;
import server.gameserver.packets.server_udp.UpdateModel;
import server.gameserver.packets.server_udp.ZoningEnd;
import server.tools.Out;
import server.tools.Timer;

/**
 * Handles incoming 0x03 sync packets from the client.
 *
 * A bare 0x03 sync packet is sent by the NCE 2.5 client after it has finished
 * loading a zone and wants to notify the server it is ready to receive further
 * updates. The server's reply must include:
 * <ul>
 *   <li>A {@link UDPAlive} ack.</li>
 *   <li>Optionally re-broadcast zone-resident NPCs / players.</li>
 *   <li>A {@link ZoningEnd} terminator to release the loading screen.</li>
 * </ul>
 *
 * Historically (irata r19) this decoder did nothing, which is why the modern
 * client hangs on the loading screen after zoning. This version now performs
 * the minimum work required to unblock the client.
 */
public class SyncUDP extends GamePacketDecoderUDP {

    public SyncUDP(DatagramPacket dp) {
        super(dp);
    }

    public void execute(Player pl) {
        if (pl == null || pl.getUdpConnection() == null) {
            return;
        }
        Out.writeln(Out.Info, "SyncUDP received from " + (pl.getAccount() != null
                ? pl.getAccount().getUsername() : "unknown")
                + " — sending sync response");

        // Immediate ack so the client stops its sync retransmit timer.
        try {
            pl.send(new UDPAlive(pl));
        } catch (Exception e) {
            Out.writeln(Out.Error, "SyncUDP: UDPAlive send failed: " + e.getMessage());
        }

        // Re-broadcast zone state shortly after; this gives any late zone
        // registrations a chance to settle before we stream them.
        pl.addEvent(new SyncZoneBroadcast());
    }

    /**
     * Delayed broadcast of zone-resident NPCs/players plus a ZoningEnd
     * terminator. Runs as its own event so the preceding UDPAlive actually
     * leaves the socket before we start the flood.
     */
    static class SyncZoneBroadcast extends DummyEvent {
        public SyncZoneBroadcast() {
            eventTime = Timer.getRealtime() + 50;
        }

        @Override
        public void execute(Player pl) {
            if (pl == null || pl.getZone() == null) {
                return;
            }
            try {
                pl.send(new UpdateModel(pl));
            } catch (Exception e) {
                Out.writeln(Out.Error, "SyncZoneBroadcast: UpdateModel failed: " + e.getMessage());
            }
            try {
                pl.getZone().sendNPCsinZone(pl);
            } catch (Exception e) {
                Out.writeln(Out.Error, "SyncZoneBroadcast: sendNPCsinZone failed: " + e.getMessage());
            }
            try {
                pl.getZone().sendPlayersinZone(pl);
            } catch (Exception e) {
                Out.writeln(Out.Error, "SyncZoneBroadcast: sendPlayersinZone failed: " + e.getMessage());
            }
            try {
                pl.send(new ZoningEnd(pl));
            } catch (Exception e) {
                Out.writeln(Out.Error, "SyncZoneBroadcast: ZoningEnd failed: " + e.getMessage());
            }
        }
    }
}
