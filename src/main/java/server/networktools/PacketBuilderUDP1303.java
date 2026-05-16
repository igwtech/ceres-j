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
		// Record EVERY reliable sub-packet (seq -> inner body) into
		// the per-session ring, not just the first one.
		//
		// Finalized wire layout:
		//   [0x13][ctr LE2][ctr+sk LE2]
		//   ( [subLen LE2][sub bytes(subLen)] )+
		// where each reliable sub = [0x03][seq LE2][body...].
		//
		// newSubPacket() calls incandgetSessionCounter() for EACH
		// sub-packet, so a 3-sub burst consumes seqs N, N+1, N+2 —
		// but the old code recorded only `recordedSeq` (=N) and
		// stored the whole concatenated datagram tail as its body.
		// Result: seqs N+1, N+2 were phantom seqs the client saw on
		// the wire but the ring could never retransmit, so a C->S
		// 0x01 retransmit-request for them was answered with
		// nothing — the client NAK-flooded forever and the
		// "Synchronizing" overlay never cleared (the reliable
		// livelock that blocked the plaza_p1 -> plaza_p3 cross:
		// post-cross init burst = one multi-sub 1303 carrying
		// charinfo + worldinfo + the dest-zone NPC roster).
		if (dps != null && dps.length > 0) {
			byte[] full = dps[0].getData();
			int len = dps[0].getLength();
			int i = 5; // past [0x13][ctr LE2][ctr+sk LE2]
			while (i + 2 <= len) {
				int subLen = (full[i] & 0xFF)
						| ((full[i + 1] & 0xFF) << 8);
				i += 2;
				if (subLen <= 0 || i + subLen > len) {
					break;
				}
				// Reliable sub-packet: [0x03][seq LE2][body...].
				if (subLen >= 3 && (full[i] & 0xFF) == 0x03) {
					int seq = (full[i + 1] & 0xFF)
							| ((full[i + 2] & 0xFF) << 8);
					byte[] body = new byte[subLen - 3];
					System.arraycopy(full, i + 3, body, 0,
							body.length);
					pl.getUdpConnection().reliableRing()
							.record(seq, body);
				}
				i += subLen;
			}
		}
		return dps;
	}
}
