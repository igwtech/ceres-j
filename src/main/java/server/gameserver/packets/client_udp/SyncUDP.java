package server.gameserver.packets.client_udp;

import java.net.DatagramPacket;

import server.gameserver.GameServerUDPConnection;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

/**
 * Handles incoming transport-layer {@code 0x03} packets from the client.
 *
 * <p><b>Format (retail NCE 2.5.x, 8-byte plaintext):</b>
 * {@code [0x03][id LE4][0x01][port LE2]}, e.g. retail
 * {@code 03 bd7e0100 01 fca6}. It is the client's answer to the
 * server's transport {@code 0x04} UDPAlive: in all 39 retail harness
 * traces that captured the UDP handshake, the client sends exactly one
 * {@code 0x03} within ~1 ms of each S→C {@code 0x04} (1:1, 2:2, 4:4
 * counts). It is
 * NOT an ack for {@code 0x13 → 0x03} reliable sub-packets — those are
 * never acknowledged positively (see {@code GamePacketReaderUDP}).
 *
 * <p>The server sends no wire reply. It records the acknowledgement on
 * the UDP connection so {@link
 * server.gameserver.internalEvents.UDPAliveHeartbeat} stops retrying
 * the {@code 0x04}: retail re-sends UDPAlive only while the client has
 * not answered (RETAIL_HANNIBAL: retries at +3.2 s / +6.4 s while the
 * client was still loading), and an extra {@code 0x04} after the
 * answer makes the client reset its reliable state.
 *
 * <p><b>Why there is no reply:</b> an earlier version of this handler
 * replied to every sync with a full zone re-broadcast (UpdateModel +
 * NPCs + players + ZoningEnd), which generated ~4500 syncs in a few
 * seconds and kept the client on "SYNCHRONIZING INTO CITY ZONE"
 * (retail NEVER sends ZoningEnd there — see
 * {@code docs/retail_burst_analysis.md} §1). A later TimeSync reply
 * burned a reliable sequence number; if the sync arrived from the OLD
 * port during BSP load the TimeSync went to a closed socket and left a
 * gap in the out-of-order list → 10 s disconnect ("Msg num X behind").
 */
public class SyncUDP extends GamePacketDecoderUDP {

    public SyncUDP(DatagramPacket dp) {
        super(dp);
    }

    @Override
    public void execute(Player pl) {
        GameServerUDPConnection con = pl.getUdpConnection();
        if (con != null) {
            con.markAliveAcked();
        }
    }
}
