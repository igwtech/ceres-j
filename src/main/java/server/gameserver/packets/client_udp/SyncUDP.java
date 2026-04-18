package server.gameserver.packets.client_udp;

import java.net.DatagramPacket;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

/**
 * Handles incoming 0x03 sync packets from the client.
 *
 * <p><b>Format (retail NCE 2.5.x, 8-byte plaintext):</b> a bare 0x03 packet
 * is the client's ACK for a server-side reliable ({@code 0x13 -> 0x03}) packet
 * it has just received. The payload identifies the sequence counter of the
 * acknowledged reliable. The server's ONLY obligation is to advance its
 * internal reliable-sent-but-unacked marker; there is NO wire reply.
 *
 * <p><b>Why this is a no-op:</b> an earlier version of this handler replied
 * to every sync with a full zone re-broadcast (UpdateModel + NPCs + players
 * + ZoningEnd). Because each reliable packet the server sent produced a
 * client sync ACK, and each sync ACK produced a new burst of reliable
 * packets, the loop generated ~4500 syncs in a few seconds. The client
 * stayed stuck on "SYNCHRONIZING INTO CITY ZONE" because we kept shoving it
 * back into the load state with repeated {@code ZoningEnd} (which retail
 * captures confirm retail NEVER sends — see
 * {@code docs/retail_burst_analysis.md} §1).
 *
 * <p>The reliable-window bookkeeping still needs to be implemented so we
 * stop retransmitting ACKed packets, but doing nothing here is strictly
 * better than the feedback storm the previous version produced.
 */
public class SyncUDP extends GamePacketDecoderUDP {

    public SyncUDP(DatagramPacket dp) {
        super(dp);
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || pl.getUdpConnection() == null) return;

        // The bare 0x03 sync packet serves TWO purposes:
        //
        // 1. ACK for a server-side reliable (just a counter echo — no
        //    reply needed).
        //
        // 2. "Sync req send" from the client's state 3/6 in
        //    FUN_0055bdc0: the client is waiting for a 0x03→0x0d
        //    TimeSync reply to advance to state 4 (in-world).
        //    Without this reply the client retries every 8 seconds
        //    and aborts after 5 attempts (~25 s) with
        //    "Synchronization with worldserver failed."
        //
        // We can't distinguish (1) from (2) at the wire level, so
        // we reply with TimeSync to every sync. The TimeSync reply is
        // a single reliable 0x03→0x0d packet — much cheaper than the
        // old zone re-broadcast that caused the feedback storm. The
        // client processes it idempotently: if already in state 4 the
        // TimeSync is just a time correction, not a state transition.

        pl.send(new server.gameserver.packets.server_udp.TimeSync(pl, 0));
    }
}
