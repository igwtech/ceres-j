package server.gameserver;

import java.util.Iterator;
import java.util.LinkedList;

import server.database.accounts.Account;
import server.tools.Out;

public class PlayerManager {

	private static LinkedList<Player> playerList = new LinkedList<Player>();

	public static Player findPlayer(Account ua) {
		//Player pl = null;
		synchronized (playerList) {
			for (Iterator<Player> i = playerList.iterator(); i.hasNext();) {
				Player pl = i.next();
				if (pl.getAccount() == ua) {
					return pl;
				}
			}
			Player pl = new Player(ua);
			pl.start();
			playerList.add(pl);
			return pl;
		}
	}

	public static void remove(Player player) {
		synchronized (playerList) {
			for (Iterator<Player> i = playerList.iterator(); i.hasNext();) {
				Player pl = i.next();
				if (pl == player) {
					i.remove();
					return;
				}
			}
		}		
	}

	public static Player getPlayer(byte[] sid) {
		synchronized (playerList) {
			for (Iterator<Player> i = playerList.iterator(); i.hasNext();) {
				Player pl = i.next();
				byte[] sid2 = pl.getSessionID();
				if (sid[0] != sid2[0]) continue;
				if (sid[1] != sid2[1]) continue;
				if (sid[2] != sid2[2]) continue;
				if (sid[3] != sid2[3]) continue;
				if (sid[4] != sid2[4]) continue;
				if (sid[5] != sid2[5]) continue;
				if (sid[6] != sid2[6]) continue;
				if (sid[7] != sid2[7]) continue;
				return pl;
//				if (pl.getSessionID().equals(sid)) {
//					return pl;
//				}
			}
			return null;
		}
	}
	
	public static Player getPlayerAwaitingUDP() {
		synchronized (playerList) {
			Out.writeln(Out.Info, "getPlayerAwaitingUDP: playerList size=" + playerList.size());
			for (Iterator<Player> i = playerList.iterator(); i.hasNext();) {
				Player pl = i.next();
				Out.writeln(Out.Info, "  player '" + pl.getAccount().getUsername()
					+ "' udpConn=" + (pl.getUdpConnection() == null ? "null" : "set"));
				if (pl.getUdpConnection() == null) {
					return pl;
				}
			}
			return null;
		}
	}

	/**
	 * Finds the player who is awaiting a UDP zone-handoff handshake from the
	 * given source IP. This is the reconnect that the NC2 client performs
	 * after it finishes loading a zone: it closes its login UDP socket and
	 * opens a fresh one from a new ephemeral port. The source IP is stable,
	 * but the source port changes.
	 *
	 * Multi-boxed clients can share a source IP, so this method filters to
	 * players that have been explicitly marked as awaiting a handoff
	 * ({@link Player#markHandoffPending()}, set at the end of
	 * {@code WorldEntryEvent}) and returns the one with the EARLIEST handoff
	 * timestamp — FIFO matches the order in which the clients finished
	 * loading the zone.
	 *
	 * @return the matched player, or {@code null} if no player on that IP
	 *         is currently awaiting a handoff.
	 */
	public static Player getPlayerByUdpAddress(java.net.InetAddress address) {
		if (address == null) return null;
		synchronized (playerList) {
			Player best = null;
			for (Iterator<Player> i = playerList.iterator(); i.hasNext();) {
				Player pl = i.next();
				if (!pl.isHandoffPending()) continue;
				server.gameserver.GameServerUDPConnection con = pl.getUdpConnection();
				if (con == null) continue;
				if (!address.equals(con.getAddress())) continue;
				if (best == null || pl.getHandoffPendingAt() < best.getHandoffPendingAt()) {
					best = pl;
				}
			}
			return best;
		}
	}

	public static LinkedList<Player> getPlayers() {
		synchronized (playerList) {
			return new LinkedList<Player>(playerList);
		}
	}
}
