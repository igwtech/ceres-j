package server.networktools;

import java.net.DatagramPacket;

import server.gameserver.Player;

/**
 * Reliable {@code 0x13 → 0x03} packet builder. Each emit gets a
 * monotonically-increasing {@code seq LE2} (the "sub-packet sequence
 * counter" the client uses to request retransmits via C→S
 * {@code 0x01 [seq LE2]} ack-requests).
 *
 * <p>On finalize ({@link #getDatagramPackets()}), this builder
 * records the emitted {@code (seq, sub-op + body)} pair into the
 * session's {@link ReliablePacketRing} so the
 * {@code ReliableAckSubPacket} handler can satisfy a future
 * retransmit request without re-running the original event handler.
 */
public class PacketBuilderUDP1303 extends PacketBuilderUDP13 {

	/** Sequence counter assigned to this packet at constructor
	 *  time — used as the ring key on finalize. The bytes are
	 *  also written into the body at wire offset 8..9; we cache
	 *  separately so getDatagramPackets() doesn't have to re-read
	 *  them from the byte buffer. */
	private final int recordedSeq;

	public PacketBuilderUDP1303(Player pl) {
		super(pl);
		write(3);
		this.recordedSeq =
				pl.getUdpConnection().incandgetSessionCounter();
		writeShort(recordedSeq);
	}

	public void newSubPacket() {
		super.newSubPacket();
		write(3);
		writeShort(pl.getUdpConnection().incandgetSessionCounter());
	}

	/** Finalize the packet AND record into the per-session ring
	 *  buffer for retransmit-on-demand. The ring stores the
	 *  inner sub-packet body (post {@code [03][seq LE2]} = the
	 *  bytes from wire offset 10 onward) — that's what would be
	 *  re-wrapped for an S→C 0x02 retransmit if the client
	 *  requested it.
	 *
	 *  <p>Idempotent: the ring's {@link
	 *  ReliablePacketRing#record(int, byte[])} is no-op-safe on
	 *  re-record (it overwrites). PacketBuilderUDP13's
	 *  {@code isFinished} guard prevents double-finalize, so
	 *  this is called at most once per packet anyway. */
	@Override
	public DatagramPacket[] getDatagramPackets() {
		DatagramPacket[] dps = super.getDatagramPackets();
		// Extract sub-packet body for ring storage:
		//   wire layout: [0x13][counter LE2][counter+sk LE2][size LE2]
		//                [0x03][seq LE2][body...]
		// = 10-byte prefix; body starts at offset 10.
		if (dps != null && dps.length > 0) {
			byte[] full = dps[0].getData();
			int len = dps[0].getLength();
			if (len >= 10) {
				byte[] body = new byte[len - 10];
				System.arraycopy(full, 10, body, 0, body.length);
				pl.getUdpConnection().reliableRing()
						.record(recordedSeq, body);
			}
		}
		return dps;
	}
}
