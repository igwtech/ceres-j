package server.networktools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import server.interfaces.ServerTCPPacket;

public class PacketBuilderTCP extends ByteArrayOutputStream implements ServerTCPPacket {

	public PacketBuilderTCP() {
		super();
		write(0xfe);
		write(0x00);
		write(0x00);
	}
	
	public PacketBuilderTCP(byte[] bs) {
		super();
		write(0xfe);
		write(0x00);
		write(0x00);
		write(bs);
	}

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

	public void write(byte[] data) {
		try {
			super.write(data);
		} catch (IOException e) {
		}
	}
	
	public byte[] getData() {
		int datasize = count - 3;
		buf[1] = (byte) datasize;
		buf[2] = (byte) (datasize >> 8);
		return buf;
	}
}
