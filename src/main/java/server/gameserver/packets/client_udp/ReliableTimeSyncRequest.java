package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.TimeSync;
import server.tools.Out;

/**
 * Handles a reliable {@code 0x03 -> 0x0d} TimeSync request from the client.
 *
 * <p>The client sends this from state 3/6 (sync-req) every 8 seconds.
 * After 5 failed retries without a reply, it aborts with "Synchronisation
 * to WorldServer failed." Replying with a {@link TimeSync} advances the
 * client's state machine from state 3/6 to state 4 (in-world, playable).
 *
 * <p>Sub-packet layout: {@code [0x03][seq_lo][seq_hi][0x0d][client_time LE4]}
 * <br>At construction time, bytes 0–3 have been consumed by the dispatcher.
 * The remaining inner payload is the 4-byte client time.
 *
 * <p>See {@code FUN_0055b6f0} case 3 in Ghidra decompile:
 * <pre>
 *   if (*(int *)param_2 == 0xd) {
 *       if (state == 3 || state == 6) {
 *           // process time sync, advance to state 4
 *           *(in_ECX + 0x2ac) = 4;  // STATE 4 = IN-WORLD
 *       }
 *   }
 * </pre>
 */
public class ReliableTimeSyncRequest extends GamePacketDecoderUDP {

    public ReliableTimeSyncRequest(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || pl.getUdpConnection() == null) return;

        // Skip past 0x03 + seq(2) + 0x0d = 4 bytes to reach the inner payload
        skip(4);
        int clientTime = readInt();

        Out.writeln(Out.Info, "ReliableTimeSyncRequest: client sync-req time="
                + clientTime + " for " + (pl.getAccount() != null
                ? pl.getAccount().getUsername() : "?")
                + " — replying with TimeSync to advance state 3/6 → 4");

        pl.send(new TimeSync(pl, clientTime));
    }
}
