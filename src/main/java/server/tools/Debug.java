package server.tools;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import server.exceptions.StartupException;
import server.gameserver.GameServerTCPConnection;
import server.gameserver.GameServerUDPConnection;
import server.gameserver.Player;
import server.interfaces.GameServerEvent;
import server.interfaces.ServerTCPPacket;
import server.interfaces.ServerUDPPacket;
import server.patchserver.packets.PatchPacketDecoderTCP;

public final class Debug {

	private static boolean debugUnknownPackets;
	private static boolean debugSendingPackets;
	private static boolean debugEvents;
        private static boolean debugReceivedPackets;
	private static boolean debugSubPackets;
	/** task #198: parsed in/out wire log (the {@code wire} token). */
	private static boolean debugWire;

	public static void init() throws StartupException {
		debugUnknownPackets = Config.debugUnknownPackets;
		debugSendingPackets = Config.debugSendingPackets;
		debugEvents = Config.debugEvents;
                debugReceivedPackets = Config.debugReceivedPackets;
		debugSubPackets = Config.debugSubPackets;
		debugWire = Config.debugWire;
		if (debugUnknownPackets | debugEvents | debugSendingPackets | debugReceivedPackets | debugSubPackets | debugWire) {
			try {
				Out.fw_debug = new FileWriter("log" + File.separatorChar + "debug.log", true);
			} catch (IOException e) {
				e.printStackTrace();
				throw new StartupException("Failed opening debug log file.");
			}		
			Out.writeln(Out.Info, "Debug logging started");
		}
	}

	public static void stopServer() {
		if (debugUnknownPackets | debugEvents | debugSendingPackets | debugReceivedPackets | debugSubPackets | debugWire) {
			try {
				Out.fw_debug.close();
			} catch (IOException e) {
				// Ignored; debug log file already closed
			}
			Out.writeln(Out.Info, "Debug logging stopped");
		}
	}

	public static void unknownPacket(String s) {
		if (debugUnknownPackets) {
			Out.debug("Unknown", s);
		}
	}

	/**
	 * True when the operator has opted into per-sub-packet diagnostic
	 * traces. UDP gameplay traffic generates these at ~90 Hz per
	 * player, so callers must check this before formatting log lines
	 * to avoid the string-build cost on the hot path.
	 */
	public static boolean isSubPacketsEnabled() {
		return debugSubPackets;
	}

	public static void subPacket(String s) {
		if (debugSubPackets) {
			Out.debug("SubPkt", s);
		}
	}

	/** Test seam: force the flag value (only used by unit tests). */
	static void setSubPacketsEnabledForTest(boolean v) {
		debugSubPackets = v;
	}

	/**
	 * True when the operator opted into the parsed in/out wire log
	 * (task #198, {@code Debug = wire}). {@link server.tools.WireLog}
	 * checks this <em>before</em> any string-format work so the
	 * feature is genuinely zero-overhead when off.
	 */
	public static boolean isWireEnabled() {
		return debugWire;
	}

	/** Test seam: force the wire flag value (only unit tests). */
	public static void setWireEnabledForTest(boolean v) {
		debugWire = v;
	}

	public static void event(GameServerEvent e, Player p) {
		if (debugEvents) {
			GameServerTCPConnection tcp = p.getTcpConnection();
			String text = "Player";
			if (tcp != null) {
				int socket = tcp.getPort();
				text += " tcp_port=" + socket;
			}
			text += " thread=" + Thread.currentThread().getName() + " executes event " + e.getClass().getName();				
			Out.debug("Event", text);
		}
	}

	public static void event(GameServerEvent e, GameServerTCPConnection tcp) {
		if (debugEvents) {
			String text = "TCPConnection tcp_port=" + tcp.getPort() + " thread=" + Thread.currentThread().getName() + " executes event " + e.getClass().getName();
			Out.debug("Event", text);
		}
	}

	public static void sendPacket(ServerTCPPacket packet, GameServerTCPConnection tcp) {
		if (debugSendingPackets) {
			String text = "TCPConnection tcp_port=" + tcp.getPort() + " thread=" + Thread.currentThread().getName() + " sends packet " + packet.getClass().getName();
			Out.debug("Sending", text);
		}
	}

	public static void sendPacket(ServerUDPPacket packet, GameServerUDPConnection udp) {
		if (debugSendingPackets) {
			String text = "UDPConnection tcp_port=" + udp.getPort() + " thread=" + Thread.currentThread().getName() + " sends packet " + packet.getClass().getName();
			Out.debug("Sending", text);
		}		
	}
        private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
        public static String bytesToHex(byte[] bytes) {
            char[] hexChars = new char[bytes.length * 2];
            for (int j = 0; j < bytes.length; j++) {
                int v = bytes[j] & 0xFF;
                hexChars[j * 2] = HEX_ARRAY[v >>> 4];
                hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
            }
            return new String(hexChars);
        }
        public static void receivedPacket(byte[] packet) {
            if(debugReceivedPackets) {
                String text = "Packet Received: " + bytesToHex(packet);
            }
        }
}
