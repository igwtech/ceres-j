package server.networktools;

import java.net.DatagramPacket;

import server.gameserver.Player;

public class PacketBuilderUDP13 extends PacketBuilderUDP {

	private int sizeposition;
	private boolean isFinished;
	protected Player pl;

	public PacketBuilderUDP13(Player pl) {
		write(0x13);
		writeShort(0); //Counter
		writeShort(0); //Counter + (udp)sessionkey
		sizeposition = count;
		writeShort(0);	// sub-packet length (2 bytes LE — retail format)
		isFinished = false;
		this.pl = pl;
	}

	public DatagramPacket[] getDatagramPackets() {
		if (!isFinished) {
			int tmp_count = count;
			count = 1;
			int udpSessionCounter = pl.getUdpConnection().getSessionCounter();
			int sessionkey = pl.getUdpConnection().getUdp13Sessionkey();
			writeShort(udpSessionCounter);
			writeShort(udpSessionCounter + sessionkey);
			count = sizeposition;
			writeShort(tmp_count - sizeposition - 2);  // 2-byte LE sub-packet length
			count = tmp_count;
			isFinished = true;
		}

		DatagramPacket[] dps = new DatagramPacket[1];
		dps[0] = new DatagramPacket(buf, count);
		return dps;
	}

	public void newSubPacket() {
		int tmp_count = count;
		count = sizeposition;
		writeShort(tmp_count - sizeposition - 2);  // 2-byte LE sub-packet length
		count = tmp_count;
		sizeposition = count;
		writeShort(0);	// next sub-packet length placeholder (2 bytes LE)
	}
}
