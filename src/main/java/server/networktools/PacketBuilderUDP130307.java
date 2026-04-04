package server.networktools;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;

import server.gameserver.Player;

public class PacketBuilderUDP130307 extends PacketBuilderUDP {

	ByteArrayOutputStream complete = new ByteArrayOutputStream();
	byte currentSection = 0;
	private Player pl;
	
	public PacketBuilderUDP130307(Player pl) {
		this.pl = pl;
	}
	
	
	public void newSection(int i) {
		if (currentSection != 0) {
			complete.write(currentSection);
			complete.write(this.count);
			complete.write(this.count >> 8);
		}
		complete.write(buf, 0, count);
		count = 0;
		currentSection = (byte) i;
	}

	public DatagramPacket[] getDatagramPackets() {
		newSection(0);
		int packets = ((complete.size() -1) /220) +1;
		byte[] completedata = complete.toByteArray();
		DatagramPacket[] dps = new DatagramPacket[packets];
		for (int i = 0; i < packets; i++) {
			int size = completedata.length - (i*220);
			if (size > 220) {
				size = 220;
			}
			PacketBuilderUDP1303 pb = new PacketBuilderUDP1303(pl);
			pb.write(7); //subcontainer
			pb.writeShort(i);
			pb.writeShort(packets);
			pb.write(13); //chain key
			pb.write(completedata, i*220, size);
			dps[i] = pb.getDatagramPackets()[0];
		}
		return dps;
	}
}
