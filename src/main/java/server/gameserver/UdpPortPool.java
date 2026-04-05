package server.gameserver;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Fixed-range pool of UDP port numbers reserved per session.
 *
 * <p>The NC2 retail server allocates a dedicated UDP port per login; the port
 * is sent to the client in the {@code UDPServerData} TCP packet, and all
 * subsequent game UDP traffic for that session is routed on it. The server
 * socket's port therefore acts as the session identifier, which is what
 * allows multi-boxed clients on a shared source IP to be disambiguated
 * without any session token in the UDP payload.
 *
 * <p>This pool matches that design by handing out free ports from a
 * configurable range (default 5001-5999) and accepting them back on
 * disconnect. Allocation is O(1) and thread-safe.
 */
public final class UdpPortPool {

	/** Inclusive lower bound of the pool. */
	public static final int MIN_PORT = 5001;
	/** Inclusive upper bound of the pool. */
	public static final int MAX_PORT = 5999;

	private static final Deque<Integer> available = new ArrayDeque<>(MAX_PORT - MIN_PORT + 1);

	static {
		for (int p = MIN_PORT; p <= MAX_PORT; p++) {
			available.offer(p);
		}
	}

	private UdpPortPool() {
	}

	/**
	 * @return a free port number, or {@code null} if the pool is exhausted.
	 */
	public static synchronized Integer allocate() {
		return available.poll();
	}

	/**
	 * Returns a port to the pool. Silently ignored if the port is outside
	 * the managed range (legacy ports like 5000 are not pooled).
	 */
	public static synchronized void release(int port) {
		if (port >= MIN_PORT && port <= MAX_PORT) {
			available.offer(port);
		}
	}

	public static synchronized int freeCount() {
		return available.size();
	}
}
