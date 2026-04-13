package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.gameserver.Zone;
import server.networktools.PacketBuilderUDP;

/**
 * Plaintext UDP equivalent of the retail NetHost "world name" message
 * (first byte 0x83, sub-opcode 0x0c). The retail server delivers this
 * through its NetMgr session layer; Ceres-J reproduces it as a raw UDP
 * datagram sent on the per-player game UDP socket and hopes the client's
 * UDP dispatcher routes first-byte 0x83 packets into the NetMgr receive
 * queue the same way it routes 0x13 gamedata.
 *
 * <h2>Why this exists</h2>
 *
 * Ghidra analysis of the modern NCE 2.5.x WorldClient state machine
 * (src in {@code ceres-j/docs/state_2ac_callsites.txt}) shows:
 *
 * <ul>
 *   <li>{@code FUN_0055ceb0} is the master tick. It calls
 *       {@code FUN_0055aa30} (NetMgr message pump) every tick, then
 *       {@code FUN_0055b6f0} (the state machine) when {@code +0x2a8 ∈
 *       {2,3,4}}.</li>
 *   <li>The state field at {@code +0x2ac} transitions
 *       {@code 1 → 2 → (block) → 5 → 6 → 4}.</li>
 *   <li>State 2 has a 15-second timeout in {@code FUN_0055b6f0} case 2
 *       that bounces the client to the login screen with
 *       "Connection to worldserver failed". This is EXACTLY the symptom
 *       we see.</li>
 *   <li>The ONLY writer that advances state past 2 on the normal path is
 *       {@code FUN_0055aa30} case {@code '\f'} (sub-opcode 0x0c,
 *       "world name received"), which sets {@code +0x2ac = 5},
 *       {@code +0x2c4 = 0x0101}, and copies a world filename into
 *       {@code +0x2c0}.</li>
 * </ul>
 *
 * So unblocking the state machine requires that case {@code 0x0c} fires.
 * That in turn requires that a message with first byte {@code 0x83}
 * reaches the client's NetMgr queue. This packet is our plaintext
 * candidate for such a message.
 *
 * <h2>Wire layout</h2>
 * <pre>
 *   offset  size  description
 *   0x00    1     0x83         NetHost msg marker (= -0x7d in the decompile)
 *   0x01    1     0x0c         sub-opcode "world name"
 *   0x02    4     int32        posX  → WorldClient +0x150
 *   0x06    4     int32        posY  → WorldClient +0x154
 *   0x0a    4     int32        posZ  → WorldClient +0x158
 *   0x0e    N+1   cstring      worldname (null-terminated, up to 0x7f chars)
 * </pre>
 *
 * The client expands {@code worldname} into a BSP path using two rules:
 * if the name starts with {@code "terrain/"} the path becomes
 * {@code ".\<name>.bsp"}; otherwise {@code ".\worlds\<name>.bsp"}. This
 * class uses whatever {@link Zone#getWorldname()} returns, which is the
 * same string the TCP {@code Location} packet already sends, so the BSP
 * that loads is identical.
 *
 * <h2>What's uncertain</h2>
 *
 * It is not yet confirmed that sending first-byte {@code 0x83} through
 * the game UDP socket routes to the NetMgr queue. Retail uses a single
 * UDP port for all traffic (verified via strace — one destination port
 * 5002, no separate NetMgr port), so either:
 * <ol>
 *   <li>the client's top-level UDP dispatcher routes first-byte
 *       {@code 0x83} packets to NetMgr (our hope), or</li>
 *   <li>NetMgr traffic is wrapped inside a {@code 0x13} gamedata header
 *       like other sub-packets, in which case this raw {@code 0x83}
 *       packet will be silently dropped.</li>
 * </ol>
 *
 * If case (2) is reality, this class will be revisited to add the
 * correct wrapping. Either way, the wire layout of the payload above
 * matches what {@code FUN_0055aa30} case {@code '\f'} reads.
 */
public class NetHostWorldName extends PacketBuilderUDP {

    public NetHostWorldName(Player pl) {
        Zone zone = pl.getZone();
        String worldname = zone != null ? zone.getWorldname() : "plaza_p1";
        if (worldname == null) {
            worldname = "plaza_p1";
        }

        write(0x83);                // NetHost msg marker
        write(0x0c);                // sub-opcode "world name"
        writeInt(0);                // posX — we don't have a meaningful start position; zero is accepted
        writeInt(0);                // posY
        writeInt(0);                // posZ
        byte[] nameBytes = worldname.getBytes();
        write(nameBytes);
        write(0);                   // C-string null terminator
    }
}
