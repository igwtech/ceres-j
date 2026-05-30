package server.infoserver.packets.server_tcp;

import java.net.Inet4Address;
import java.net.UnknownHostException;

import server.gameserver.PlayerManager;
import server.infoserver.InfoServerConnection;
import server.networktools.PacketBuilderTCP;
import server.tools.Config;

/**
 * InfoServer {@code 0x8383 ServerList} — reply to the client's
 * {@code 0x8482 GetCharList} on the InfoServer :7000 connection.
 * Lists the game servers the account can play on (NC2 retail only
 * ever exposes one, "titan").
 *
 * <p>The 26-byte body is a count-prefixed array of fixed-shape
 * server records. See {@code docs/protocol/packets/tcp_s2c_8383.md}
 * for the full wire layout.
 *
 * <h3>online_users field</h3>
 *
 * <p>Verified against 15 retail captures: this LE16 carries the
 * <strong>absolute count of online players</strong>, not a
 * percentage (retail values range 3-25; the upper byte is always
 * zero). Ceres-J reports the live count from
 * {@link PlayerManager#getOnlinePlayers}, capped to the LE16 max
 * (0xFFFF) for safety.
 */
public class ServerList extends PacketBuilderTCP {

	/** Server config revision flag — retail samples 2026-05 carry
	 *  {@code 0x0007}; pre-2026-05 carried {@code 0x0006}. Best
	 *  reading is a server config / patch generation counter.
	 *  Pinned to the current-era value. */
	static final int CONFIG_REVISION_FLAG = 0x0007;

	public ServerList(InfoServerConnection isc) {
		String ip = isc.getServerIP();
		byte[] serverip = new byte[4];
		try {
			serverip = Inet4Address.getByName(ip).getAddress();
		} catch (UnknownHostException e) {
			// Failed to resolve server IP; using default empty address
		}
		byte[] serverName = Config.getProperty("ServerName").getBytes();

		write(0x83);// packet id
		write(0x83);
		writeShort(1);// number of servers
		writeShort(0xe);// size of serverstructure

		//per server:
		write(serverip);
		writeInt(12000);//port number
		write(serverName.length+1);
		write(Integer.parseInt(Config.getProperty("CharsPerAccount")));
		writeShort(currentOnlinePlayerCount());
		writeShort(CONFIG_REVISION_FLAG);
		write(serverName);
		write(0); //Cstyle
	}

	/**
	 * Returns the current online-player count, clamped to the LE16
	 * range so the wire field never overflows. Package-private so the
	 * unit test can exercise the clamp without touching the static
	 * PlayerManager state.
	 */
	static int currentOnlinePlayerCount() {
		int n;
		try {
			n = PlayerManager.getOnlinePlayers().size();
		} catch (Throwable t) {
			// Defensive: if PlayerManager isn't initialised (early
			// boot / unit-test contexts) the InfoServer reply should
			// still go out with a sensible 0 instead of crashing.
			n = 0;
		}
		return clampToUint16(n);
	}

	/** Clamp to the LE16 wire range [0, 0xFFFF]. */
	static int clampToUint16(int n) {
		if (n < 0) return 0;
		if (n > 0xFFFF) return 0xFFFF;
		return n;
	}
}
