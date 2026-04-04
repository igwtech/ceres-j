package server.networktools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;

import server.interfaces.ServerUDPPacket;

public class PacketBuilderUDP  extends ByteArrayOutputStream implements ServerUDPPacket {

	public void writeInt(int i) {
		write(i);
		write(i >> 8);
		write(i >> 16);
		write(i >> 24);
	}

	public void writeShort(int i) {
		write(i);
		write(i >> 8);
	}

	public void writeFloat(float f) {
		writeInt(Float.floatToIntBits(f));
	}

	public void write(byte[] data) {
		try {
			super.write(data);
		} catch (IOException e) {
			// ByteArrayOutputStream.write(byte[]) should not throw
		}
	}

	public DatagramPacket[] getDatagramPackets() {
		DatagramPacket[] dps = new DatagramPacket[1];
		dps[0] = new DatagramPacket(buf, count);
		return dps;
	}

}
