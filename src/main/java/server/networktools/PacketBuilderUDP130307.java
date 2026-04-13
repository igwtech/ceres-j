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
		byte[] completedata = complete.toByteArray();
		int totalSize = completedata.length;

		// Retail multipart fragments carry a 6-byte per-fragment header in
		// front of every chunk:
		//   [0x00][discriminator][total_size LE4]
		// The discriminator is 0x01 for CharInfo-like streams (triggers the
		// byte[1]==0x01 → bit 0 set branch in FUN_0055c270), 0x02 for
		// CharsysInfo-like streams (byte[1]==0x02 → bit 1). Retail emits
		// the SAME 6 bytes on every fragment of a chain; varies per session.
		// We use 0x01 so the stream advances the CharInfo sync bit (bit 0),
		// which is exactly the bit the client never sees setting today —
		// see docs/state_string_refs.txt FUN_0055c270, WorldClient.cpp:0x84e.
		final int HEADER_BYTES = 6;
		// With the 6-byte per-fragment header, payload room per fragment
		// drops from 220 -> 214 bytes of reassembled-stream data.
		final int CHUNK_BYTES = 220 - HEADER_BYTES;
		int packets = Math.max(1, (totalSize + CHUNK_BYTES - 1) / CHUNK_BYTES);

		DatagramPacket[] dps = new DatagramPacket[packets];
		for (int i = 0; i < packets; i++) {
			int offset = i * CHUNK_BYTES;
			int size = Math.min(CHUNK_BYTES, totalSize - offset);

			PacketBuilderUDP1303 pb = new PacketBuilderUDP1303(pl);
			pb.write(7); //subcontainer (multipart sub-type 0x07)
			pb.writeShort(i);
			pb.writeShort(packets);
			// chain-key byte: constant 0x00 in all 4 retail captures
			// (ACC1/ACC2 × CHAR1/CHAR2). The legacy Irata code wrote 0x0d
			// here, but retail's multipart reassembler keys on this byte
			// and mismatches cause the fragment stream to be dropped.
			pb.write(0);

			// 6-byte per-fragment header (same on every fragment).
			pb.write(0x00);            // padding / unused
			pb.write(0x01);            // discriminator → triggers CharInfo path (bit 0)
			pb.write(totalSize & 0xFF);
			pb.write((totalSize >> 8) & 0xFF);
			pb.write((totalSize >> 16) & 0xFF);
			pb.write((totalSize >> 24) & 0xFF);

			pb.write(completedata, offset, size);
			dps[i] = pb.getDatagramPackets()[0];
		}
		return dps;
	}
}
