package server.gameserver.packets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.Player;
import server.interfaces.Event;
import server.interfaces.GameServerEvent;
import server.tools.Out;

public abstract class GamePacketDecoderUDP extends ByteArrayInputStream implements GameServerEvent {

	public GamePacketDecoderUDP(DatagramPacket dp) {
		super(dp.getData(), 0, dp.getLength());
	}

	public GamePacketDecoderUDP(byte[] subPacket) {
		super(subPacket, 0, subPacket.length);
	}

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

	public int read(byte[] data) {
		try {
			return super.read(data);
		} catch (IOException e) {}
		return -1;
	}

	public int readShort() {
		return read() | (read() << 8);
	}

	public int readInt() {
		return read() | (read() << 8) | (read() << 16) | (read() << 24);
	}
	
	/* 
	 * read a String of the length len - 1 starting at the current pos
	 * from the buffer
	 */
	/*public String readCString(int len) {
		String res = new String(buf, pos, len -1);
		pos += len;
		return res;
	}*/
	
	/* 
	 * read a String of the length len starting at offset(starting at 0)
	 * from the buffer
	 */
	public String readCString(int offset, int len){
		return new String(buf, offset, len);
	}
	
	/*
	 * read a String from the buffer starting at offset
	 */
	public String readCString(int offset){
		int length = buf.length - offset;
		return new String(buf, offset, length);
	}
	
	/*
	 * read a string from the buffer
	 */
	public String readCString(){
		return new String(buf);
	}
}
