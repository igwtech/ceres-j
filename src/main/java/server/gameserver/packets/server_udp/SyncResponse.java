package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Sync response packet — the 13-byte payload the client expects to
 * transition its sync state from 3/6 to 4 ("in-world").
 *
 * <p>Ghidra analysis of {@code FUN_00559920} (game UDP dispatcher in
 * {@code docs/state_string_refs.txt}) case 3:
 * <pre>
 * case 3:
 *   if (*(int *)param_2 == 0xd) {                        // length == 13
 *     if ((+0x2ac == 3) || (+0x2ac == 6)) {              // state 3 or 6
 *       // ... read payload[1..4] as server timestamp
 *       // ... read payload[5..8] as echoed client timestamp
 *       // ... read payload[9..10] and payload[11..12] as ushort
 *       *(undefined2 *)(in_ECX + 0x2ac) = 4;             // *** state = 4 ***
 *       // log "Synced :%i"
 *     }
 *   }
 * </pre>
 *
 * <p>Wire layout of the inner payload (after the {@code 0x13 -> 0x03}
 * reliable-wrapper stripping the client does):
 * <pre>
 *   offset  size  value
 *   0x00    1     0x03          sync-response opcode (distinct from the 0x03 RELIABLE wrapper)
 *   0x01    4     int32         server timestamp
 *   0x05    4     int32         echoed client timestamp (from the client's sync req)
 *   0x09    2     int16         unknown (retail observed as per-zone id or similar)
 *   0x0b    2     int16         unknown
 * </pre>
 *
 * <p>Total inner payload: 13 bytes.
 *
 * <h2>Why this isn't just TimeSync</h2>
 *
 * {@link TimeSync} uses inner sub-type {@code 0x0d} which dispatches to
 * a DIFFERENT handler (the regular time-sync keep-alive loop). The state
 * machine's "initial sync" transition (3 → 4) is handled by a SEPARATE
 * branch that dispatches on inner byte {@code 0x03}. Two different
 * packets with similar purpose but distinct opcodes.
 */
public class SyncResponse extends PacketBuilderUDP1303 {

    public SyncResponse(Player pl, int clientEchoTimestamp) {
        super(pl);
        // Inner sub-type 0x03 is what FUN_00559920 case 3 dispatches on.
        // The outer 0x03 reliable wrapper was already written by
        // PacketBuilderUDP1303's constructor; this is the inner
        // "sync response" opcode that happens to share the value.
        write(0x03);
        // payload[1..4]: server "now" timestamp. The client uses this
        // as the authoritative server time after rejecting the
        // one-way-delay from the echoed client timestamp. Any monotonic
        // value works; retail uses the server's millisecond clock.
        writeInt((int) (System.currentTimeMillis() & 0xFFFFFFFFL));
        // payload[5..8]: echo of whatever the client sent in its sync
        // req (the 5-byte 0x02-header packet). We don't know the
        // client's timestamp at this point so we echo zero — this
        // skews the computed one-way-delay but does not prevent the
        // state transition.
        writeInt(clientEchoTimestamp);
        // payload[9..10]: two shorts whose meaning is unknown. Retail
        // captures show them non-zero. Zeros are acceptable for the
        // state transition; they only affect values downstream of
        // FUN_005553f0.
        writeShort(0);
        writeShort(0);
    }
}
