package server.gameserver.packets;

import java.io.ByteArrayInputStream;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.Player;
import server.interfaces.Event;
import server.interfaces.GameServerEvent;
import server.tools.Out;

public abstract class GamePacketDecoderTCP extends ByteArrayInputStream implements GameServerEvent{

	public GamePacketDecoderTCP(byte[] arg0) {
		super(arg0);
	}

// for Event
	
	public long getEventTime() {
		return 0;
	}

	public int compareTo(Event o) {
		if (o.getEventTime() == 0) {
			return 0;
		} else {
			return -1;
		}
	}

	public void execute(GameServerTCPConnection tcp) {
		Out.writeln(Out.Error, "TCPConnection " + tcp.getName() + " executes Event Dummy of " + this.getClass().getName());
	}

	public void execute(Player pl) {
		Out.writeln(Out.Error, "Player " + pl.getName() + " executes Event Dummy of " + this.getClass().getName());
	}

// for decoding	
	
	public int readShort() {
		return read() | (read() << 8);
	}

	public int readInt() {
		return read() | (read() << 8) | (read() << 16) | (read() << 24);
	}

	public String readCString(int len) {
		if (len <= 0) return "";
		int strLen = len - 1; // exclude null terminator
		if (strLen <= 0) {
			pos += len;
			return "";
		}
		// Clamp to remaining buffer
		int available = count - pos;
		if (strLen > available) strLen = available;
		if (strLen < 0) strLen = 0;
		String res = new String(buf, pos, strLen);
		pos += len;
		return res;
	}

	public String readEncryptedString(int len, int key) {
		if (len <= 0) return "";
		byte[] passwdData = new byte[len];
		for (int i=0; i < len; i++) {
			int val = readShort();
			if (val < 0) break; // EOF
			passwdData[i] = (byte) ((val >> 4) - key);
		}
		return new String(passwdData);
	}
}


