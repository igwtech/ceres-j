package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * UDP S→C reliable {@code 0x03/0x0d} TimeSync.
 *
 * <p>Body layout (12 bytes after sub-opcode):
 *
 * <pre>
 *   offset 0..3  : server_time   LE32   game tick / monotonic counter
 *   offset 4..7  : client_time   LE32   echo of the LE32 sent in
 *                                       C→S 0x0c GetTimeSync /
 *                                       0x03/0x0d ReliableTimeSyncRequest
 *   offset 8..9  : 0xd5 0x0a       CONSTANT prefix (tag bytes,
 *                                       across all retail captures)
 *   offset 10..11: session-state LE16   varies per session
 *                                       (HANNIBAL/NORMAN/AUGUSTO =
 *                                       0x0020; DRSTONE3 = 0x0051) —
 *                                       likely zone/world-version sub-id
 * </pre>
 *
 * <p>The trailing 4-byte world_id was previously {@code fb 0a 00 00},
 * then briefly {@code d5 0a 58 00} (a single-capture extrapolation).
 * Cross-capture analysis 2026-05-09 against HANNIBAL/NORMAN/DRSTONE3/
 * AUGUSTO showed bytes 10..11 vary per session (0x0020 dominant 3/4,
 * DRSTONE3 had 0x0051). Settled on {@code d5 0a 20 00} — matches the
 * most common retail value. The pcap-replay harness masks byte 10 as
 * session-derived.
 * The Ghidra client handler at {@code FUN_0055b6f0} case 3 advances
 * the session state machine 3/6 → 4 (in-world) on TimeSync receipt; if
 * it validates these bytes, a mismatch would manifest as a perpetual
 * "Synchronisation to WorldServer failed" timeout. Even when not
 * strictly validated, matching retail keeps Ceres-J pcaps closer to
 * the catalog baseline.
 */
public class TimeSync extends PacketBuilderUDP1303 {

	/** World/server-version identifier emitted at body offset 8..11.
	 *  Constant across all 80 retail samples in the catalog. */
	public static final byte[] WORLD_ID_TAIL = {
			(byte) 0xd5, 0x0a, 0x20, 0x00
	};

	public TimeSync(Player pl, int clienttime) {
		super(pl);
		write(0x0d);
		writeInt(1);              // server_time placeholder (semantics
		                           // unconfirmed; client appears not to
		                           // validate so 1 stays for now)
		writeInt(clienttime);     // echo of client's TimeSync request
		write(WORLD_ID_TAIL);     // retail-verified constant
	}
}
