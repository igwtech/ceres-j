package server.networktools;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;

import server.gameserver.Player;

/**
 * Section-stream packet builder for `0x13/0x03/<reliable_type>` CharInfo-style
 * payloads. Builds a TLV body via {@link #newSection(int)} calls, then emits it
 * to the wire on one of two reliable sub-channels depending on body size:
 *
 * <ul>
 *   <li><b>{@code 0x03/0x2c} single packet</b> (body ≤ ~900 B). Inner data
 *   format: {@code 02 01 <body-without-leading-22>}. The retail-observed
 *   2-byte prefix substitutes for the multipart fragment header.</li>
 *   <li><b>{@code 0x03/0x07} multipart</b> (body &gt; ~900 B). Each fragment
 *   carries an 11-byte header
 *   {@code [frag_idx LE2][total_frags LE4][disc=0x01][data_size LE3][chain_key=0x00]}
 *   followed by a chunk of body bytes. Reassembled body starts with
 *   {@code 22 02 01 <sections>}.</li>
 * </ul>
 *
 * <p>Size-based channel selection verified against retail captures 2026-05-01:
 * Dr.Stone (810 B CharInfo) used {@code 0x03/0x2c} in both Genesis Dungeon
 * (port 5004) and Plaza Sec-1 (port 5008). The 5 established main-world
 * captures (1357–4130 B) all used {@code 0x03/0x07}.
 */
public class PacketBuilderUDP130307 extends PacketBuilderUDP {

	/**
	 * Maximum body size that fits in a single {@code 0x03/0x2c} packet.
	 * Above this threshold, the body is split into multipart fragments.
	 * Conservative value derived from UDP MTU (~1450 B usable) minus the
	 * cipher header (4 B), 0x13/0x03 wrapping (~5 B), reliable_type (1 B)
	 * and the 2-byte single-mode prefix.
	 */
	private static final int SINGLE_PACKET_THRESHOLD = 900;

	/** Per-fragment header size (frag_idx + total_frags + disc + data_size) for 0x07 multipart. */
	private static final int FRAGMENT_HEADER_BYTES = 6;

	/** Body bytes per fragment after subtracting the per-fragment header. */
	private static final int FRAGMENT_CHUNK_BYTES = 220 - FRAGMENT_HEADER_BYTES;

	/** Reliable sub-type IDs (verified retail). */
	private static final int RELIABLE_MULTIPART = 0x07;
	private static final int RELIABLE_SINGLE = 0x2c;

	/** Discriminator routing reassembled multipart blob to its handler. */
	private static final int DISCRIMINATOR_CHARINFO = 0x01;

	private final ByteArrayOutputStream complete = new ByteArrayOutputStream();
	private byte currentSection = 0;
	private final Player pl;

	public PacketBuilderUDP130307(Player pl) {
		this.pl = pl;
	}

	/**
	 * Begin a new section. Flushes the previous section's accumulated bytes
	 * as {@code [id u8][size LE2][body]} into the complete-body buffer, then
	 * starts a fresh section.
	 *
	 * <p>Pass {@code 0} to flush only (used by {@link #getDatagramPackets()}).
	 */
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

	@Override
	public DatagramPacket[] getDatagramPackets() {
		newSection(0); // flush trailing section
		byte[] body = complete.toByteArray();
		if (body.length <= SINGLE_PACKET_THRESHOLD) {
			return emitSingle(body);
		}
		return emitMultipart(body);
	}

	/**
	 * Emit body on reliable channel {@code 0x03/0x2c} as a single packet.
	 * Wire inner data: {@code 02 01 <body[1..]>} — the leading {@code 0x22}
	 * (multipart prelude marker) is stripped; the {@code 02} byte signals
	 * "single packet" to the client. Retail-verified format.
	 */
	private DatagramPacket[] emitSingle(byte[] body) {
		PacketBuilderUDP1303 pb = new PacketBuilderUDP1303(pl);
		pb.write(RELIABLE_SINGLE);
		// Body in 'complete' starts with `22 02 01 [sections]`. Single-packet
		// retail format is `02 01 [sections]` — drop byte 0 (`0x22`) and the
		// remaining `02 01 [sections]` is exactly what the client expects.
		pb.write(body, 1, body.length - 1);
		return new DatagramPacket[] { pb.getDatagramPackets()[0] };
	}

	/**
	 * Emit body on reliable channel {@code 0x03/0x07} as a multipart fragment
	 * stream. Each fragment carries the standard NC2 multipart header so the
	 * client's reassembler (FUN_0055c270) can route it to the CharInfo
	 * handler via {@code disc=0x01}.
	 */
	private DatagramPacket[] emitMultipart(byte[] body) {
		int totalSize = body.length;
		int packets = Math.max(1, (totalSize + FRAGMENT_CHUNK_BYTES - 1) / FRAGMENT_CHUNK_BYTES);
		DatagramPacket[] dps = new DatagramPacket[packets];
		for (int i = 0; i < packets; i++) {
			int offset = i * FRAGMENT_CHUNK_BYTES;
			int size = Math.min(FRAGMENT_CHUNK_BYTES, totalSize - offset);

			PacketBuilderUDP1303 pb = new PacketBuilderUDP1303(pl);
			pb.write(RELIABLE_MULTIPART);
			pb.writeShort(i);                 // frag_idx LE2
			pb.writeShort(packets);           // total_frags LE2 (high 2 bytes implicit zero)
			pb.write(0);                      // total_frags LE4 high byte 1
			pb.write(0x00);                   // total_frags LE4 high byte 2

			// 6-byte per-fragment header content (verified retail):
			// [discriminator=0x01][total_size LE4]. The high byte of LE4
			// doubles as the chain_key=0x00 prefix when totalSize < 2^24,
			// which it always is for CharInfo (< 16 MB).
			pb.write(DISCRIMINATOR_CHARINFO);
			pb.write(totalSize & 0xFF);
			pb.write((totalSize >> 8) & 0xFF);
			pb.write((totalSize >> 16) & 0xFF);
			pb.write((totalSize >> 24) & 0xFF); // = chain_key 0x00

			pb.write(body, offset, size);
			dps[i] = pb.getDatagramPackets()[0];
		}
		return dps;
	}
}
