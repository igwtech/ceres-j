package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Command ID acknowledgement — gamedata sub-type {@code 0x19} with
 * inner sub-opcode {@code 0x07}. Sets the client's {@code +0x2ec}
 * (command ID) field which {@code FUN_00558950} polls for during the
 * world-change state chain.
 *
 * <p>From the decompile of {@code FUN_00559920} case {@code 0x19}
 * sub {@code 0x07}:
 * <pre>{@code
 * case 7:
 *   if (((6 < uVar6) && (+0x144 != 0) && (+0x146 == 0) && (+0x2ec == -1))) {
 *     +0x2a0 = now;
 *     +0x2aa = 0;
 *     +0x2ec = *(int*)(puVar1 + 3);   // command ID from payload
 *     return 1;
 *   }
 * }</pre>
 *
 * <p>Requires: packet length > 6, {@code +0x144 != 0} (world-change mode),
 * {@code +0x146 == 0} (cmd id not yet confirmed), {@code +0x2ec == -1}
 * (no prior cmd id). The command ID value at payload offset 3 is
 * arbitrary — we use 1.
 *
 * <p>Wire layout (inner payload after {@code 0x13 → 0x03} stripping):
 * <pre>
 *   0x00  1  0x19     sub-type
 *   0x01  1  0x07     inner sub-opcode (switch case 7)
 *   0x02  1  0x00     padding (puVar1+2, skipped)
 *   0x03  4  int32    command ID → +0x2ec
 * </pre>
 * Total: 7 bytes (satisfies {@code 6 < uVar6}).
 */
public class CommandIdAck extends PacketBuilderUDP1303 {

    public CommandIdAck(Player pl, int commandId) {
        super(pl);
        write(0x19);            // sub-type
        write(0x07);            // inner sub-opcode
        write(0x00);            // padding
        writeInt(commandId);    // command ID → +0x2ec
    }
}
