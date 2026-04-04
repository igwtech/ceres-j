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
		write(0);	//size of data
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
			write(tmp_count - sizeposition -1);
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
		write(tmp_count - sizeposition -1);
		count = tmp_count;
		sizeposition = count;
		write(0);	//size of data
	}
}
