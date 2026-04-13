package server.gameserver.packets.server_udp;

import java.net.InetAddress;
import java.net.UnknownHostException;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * "New World info received" message — gamedata opcode {@code 0x19} with
 * sub-opcode {@code 0x04}. Populates the client's worldserver address
 * fields so that the subsequent "Joining session" code path has a real
 * destination to connect to.
 *
 * <h2>Background</h2>
 *
 * From the client's Init.Log the failure sequence on a Ceres-J session
 * is:
 *
 * <pre>
 *   WorldClient: Connecting to NetHost . . . succeed!
 *   WorldClient: Delete world for world change . . .
 *   WorldClient: Create new world to change to . . .
 *   WorldClient: World changes successfull!
 *   WorldClient: Joining session . . .
 *   WorldServer: Connecting to WorldServer failed!
 *   WORLDCLIENT : Connection to worldserver failed
 * </pre>
 *
 * The "Joining session" step calls {@code FUN_004cf960(+0x2cc, +0x2d0)}
 * with the worldserver IP and port stored on the WorldClient object.
 * When those fields are never populated, the client logs an uninit
 * garbage IP like
 * {@code @PWORLDHOST Connect to 1816438976, 12000} and then times out
 * after 15 seconds waiting for responses that never arrive.
 *
 * <h2>Which packet sets the fields</h2>
 *
 * Ghidra decompile of {@code FUN_00559920} case {@code 0x19} sub {@code 0x04}
 * (from {@code docs/state_string_refs.txt}):
 *
 * <pre>{@code
 * case 4:
 *   if (0x13 < uVar6) {                                   // length > 19
 *     uVar5 = *(undefined4 *)(puVar1 + 3);
 *     if (*(char *)(in_ECX + 0x144) != '\0') {            // world-change mode
 *       *(undefined4 *)(in_ECX + 0x2cc) = uVar5;          // worldserver IP
 *       *(undefined4 *)(in_ECX + 0x2d0) = *(u32*)(puVar1 + 7);   // port
 *       *(undefined4 *)(in_ECX + 0x15c) = *(u32*)(puVar1 + 0xc); // world entity
 *       *(undefined4 *)(in_ECX + 0x2ec) = *(u32*)(puVar1 + 0x10); // cmd id
 *       *(undefined1 *)(in_ECX + 0x288) = 1;
 *     } else {
 *       // staging branch: writes +0x28c/+0x290/+0x294/+0x298 + +0x288=1
 *       // for a later world-change to copy into +0x2cc/+0x2d0 etc.
 *     }
 * }</pre>
 *
 * <p>Either branch populates the data the Joining-session call needs —
 * directly if {@code +0x144 != 0} (we're mid-world-change) or via the
 * staging area for the next {@code 0x19 0x0c} "world change triggered"
 * to copy over. Sending this packet during {@code WorldEntryEvent}
 * ensures the fields are non-garbage by the time the state machine
 * reaches "Joining session".
 *
 * <h2>Wire layout of the inner payload (after {@code 0x13 → 0x03} stripping)</h2>
 * <pre>
 *   offset  size  description
 *   0x00    1     0x19           outer opcode
 *   0x01    1     0x04           sub-opcode
 *   0x02    1     0x00           padding (client skips to offset 3)
 *   0x03    4     int32 LE       worldserver IP (little-endian network byte order of server IP)
 *   0x07    4     int32 LE       worldserver UDP port (only low 16 bits used)
 *   0x0b    1     0x00           padding (client reads from offset 0x0c)
 *   0x0c    4     int32          world entity id   → +0x15c
 *   0x10    4     int32          command id        → +0x2ec
 *   0x14    1     0x00           null/padding (client length-check is {@code 0x13 < uVar6})
 * </pre>
 *
 * <p>Total: 21 bytes. Minimum is 20 (to satisfy {@code len > 19}); the
 * extra trailing byte gives safety margin.
 */
public class WorldInfoSrv extends PacketBuilderUDP1303 {

    public WorldInfoSrv(Player pl) {
        super(pl);

        // Opcode and sub-opcode.
        write(0x19);
        write(0x04);
        write(0x00); // padding so the int at offset 3 is aligned the way
                     // the client's (puVar1 + 3) read expects.

        // Worldserver IP. The client stores this as a raw int32 and later
        // passes it to WinSock's connect() via FUN_004cf960. Ceres-J
        // runs on the same host as the TCP gameserver, so Player's
        // reported server IP is the right value.
        int ipAsInt = serverIpToInt(pl.getServerIP());
        writeInt(ipAsInt);

        // Worldserver UDP port. The client reads it as int32; only the
        // low 16 bits are used. We send the per-player UDP port the
        // PlayerUdpListener is already bound to — it's what the client
        // will reach us on.
        writeInt(pl.getUdpPort());

        // Padding so offset 0xc is aligned for the next int32 read.
        write(0x00);

        // World entity id. Used by the client to identify the zone
        // object it's entering. Anything non-zero; the actual value is
        // consumed by game logic we haven't modelled. Use the mapId as
        // a stable proxy.
        writeInt(pl.getMapID());

        // Command id. Used by FUN_00558950's "Request Command ID" poll
        // loop — when this matches, the client transitions to the
        // "Client up to date" branch which sets +0x145 = 1 and
        // +0x2ac = 7, then on the next pass loads the BSP and advances
        // state to 5/6. Any non-zero value works for the first join;
        // subsequent world changes will use the server's increment.
        writeInt(1);

        // Trailing zero / padding to push length above 19.
        write(0x00);
    }

    /**
     * Parse a dotted-quad IP into a little-endian int32 matching the
     * byte order the client expects when it reads the 4-byte field.
     * On failure returns 127.0.0.1 as a harmless fallback.
     */
    private static int serverIpToInt(String ip) {
        try {
            byte[] octets = InetAddress.getByName(ip).getAddress();
            // Little-endian: octet[0] is the LSB of the int32, matching
            // the way the client reads *(u32*)(puVar1 + 3) on x86.
            return (octets[0] & 0xFF)
                 | ((octets[1] & 0xFF) << 8)
                 | ((octets[2] & 0xFF) << 16)
                 | ((octets[3] & 0xFF) << 24);
        } catch (UnknownHostException e) {
            // 127.0.0.1 in little-endian bytes
            return 0x0100007F;
        }
    }
}
