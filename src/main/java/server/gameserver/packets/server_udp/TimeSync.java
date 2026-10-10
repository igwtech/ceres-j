package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * UDP S→C reliable {@code 0x03/0x0d} TimeSync.
 *
 * <p>Body layout (12 bytes after sub-opcode):
 *
 * <pre>
 *   offset 0..3  : server_time   LE32   server uptime, milliseconds
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
 * <h3>server_time semantics</h3>
 *
 * <p>Pcap analysis 2026-05-23 of {@code RETAIL_LONG_PARTY_A}:
 * server_time is a <strong>monotonic millisecond counter</strong>
 * that advances 1ms per wall-clock ms. Across 22 retail emissions
 * in an 11-minute capture the value went from 2,966,216 to
 * 3,458,647 ({@code Δ = 492,431 ms} for {@code Δt = 8m 12s} —
 * exact ms parity). Most likely server-process uptime in ms.
 *
 * <p>Pre-fix this field was hardcoded to {@code 1}, which is why
 * the in-game HUD clock stayed frozen at {@code 00:xx} for the
 * entire session — task #231. Fixed 2026-05-23.
 *
 * <h3>The trailing 4-byte world_id</h3>
 *
 * <p>was previously {@code fb 0a 00 00}, then briefly
 * {@code d5 0a 58 00} (a single-capture extrapolation).
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

	/** One in-game day in server_time ms. The client's HUD clock is
	 *  (server_time mod this) scaled to 24 h: 2,489,023 showed ~06:56 and
	 *  80 s of uptime showed 00:13 (harness verify_pull_1 / verify_clock_1).
	 *  Same constant as {@link server.tools.Timer#getIngametime()}. */
	public static final long IN_GAME_DAY_MS = 6L * 24 * 60 * 1000;

	/** Epoch of server_time. Server start (JVM start, not class load: the
	 *  class loads on the first send, so the first TimeSync used to carry 0
	 *  or 1, then ~300 at world entry) rounded down to an in-game midnight,
	 *  so the in-game time of day follows the wall clock across restarts as
	 *  it did when the 0x0b ping carried {@code Timer.getIngametime()}.
	 *  Package-private for test injection. */
	static volatile long bootMs = epochMs();

	private static long epochMs() {
		long start = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
		return start - Math.floorMod(start, IN_GAME_DAY_MS);
	}

	/** Clock seam — defaults to {@link System#currentTimeMillis()}.
	 *  Tests inject a fake clock via {@link #setClockForTesting}. */
	private static volatile java.util.function.LongSupplier clock =
			System::currentTimeMillis;

	public TimeSync(Player pl, int clienttime) {
		super(pl);
		write(0x0d);
		writeInt(serverTimeMs());      // monotonic ms since server boot
		writeInt(clienttime);          // echo of client's TimeSync request
		write(WORLD_ID_TAIL);          // retail-verified constant
	}

	/** Current server_time value (monotonic ms since boot, truncated
	 *  to LE32). The one server clock: the 0x0b ping reply ({@link SPing})
	 *  carries it too. Retail's 0x0d and 0x0b share one ms clock
	 *  (retail_b862_1: 817,472,137 then 817,473,317 a second later);
	 *  Ceres sent 0/1 and 309 in 0x0d but ~2.79M in 0x0b, and the client
	 *  logged "WORLDMGR : PHFrame went backwards by 58 ms".
	 *
	 *  <p>Wraps at {@code 2^32 ms ≈ 49.7 days}. The retail server
	 *  presumably restarts before then; if Ceres-J ever stays up
	 *  longer the value wraps silently. Acceptable for now. */
	public static int serverTimeMs() {
		long delta = clock.getAsLong() - bootMs;
		// Clamp to non-negative just in case the test clock or NTP
		// adjusts backwards; never emit a negative LE32.
		if (delta < 0) delta = 0;
		return (int) (delta & 0xFFFFFFFFL);
	}

	/** Test seam — inject a fake clock + bootMs to exercise the
	 *  advance contract deterministically. */
	static void setClockForTesting(long boot,
	                                java.util.function.LongSupplier c) {
		bootMs = boot;
		clock = c;
	}

	/** Restore production clock. */
	static void resetClockForTesting() {
		bootMs = epochMs();
		clock = System::currentTimeMillis;
	}
}
