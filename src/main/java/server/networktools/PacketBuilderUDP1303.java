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

	/** Buffer offsets of every reliable {@code [seq LE2]} placeholder
	 *  written by this builder (one per sub-packet — the constructor's
	 *  plus every {@link #newSubPacket()}). The real seqs are assigned
	 *  and patched into the buffer at finalize time
	 *  ({@link #getDatagramPackets()}), NOT at construction.
	 *
	 *  <p>Why deferred: {@code incandgetSessionCounter()} permanently
	 *  consumes a reliable seq. If it were consumed in the constructor
	 *  (the old behaviour) and the packet then threw before reaching
	 *  the wire (a malformed item in CharInfo, an aborted NPC-roster
	 *  loop in SZoning1, …) — or was simply constructed out of send
	 *  order — that seq would be burned with no {@code 0x03/[seq]}
	 *  ever emitted, leaving a permanent GAP in the client's reliable
	 *  receive window. The client then NAK-floods (raw {@code 0x01})
	 *  the missing seq forever and the server answers each with a
	 *  {@code 0x02} retransmit — the reliable livelock diagnosed
	 *  2026-05-30 in the apartment-idle diff. Assigning the seq only
	 *  at finalize guarantees: seq-consumed ⇔ bytes-on-wire, and that
	 *  the on-wire seq order is exactly the build order. */
	private final java.util.List<Integer> seqOffsets =
			new java.util.ArrayList<>();

	/** Guards seq assignment so a double-finalize (the base header
	 *  is already {@code isFinished}-guarded) can't double-consume
	 *  the session counter. */
	private boolean seqsAssigned = false;

	public PacketBuilderUDP1303(Player pl) {
		super(pl);
		write(3);
		seqOffsets.add(count);   // remember where the seq LE2 goes
		writeShort(0);           // placeholder — patched at finalize
	}

	public void newSubPacket() {
		super.newSubPacket();
		write(3);
		seqOffsets.add(count);   // remember where the seq LE2 goes
		writeShort(0);           // placeholder — patched at finalize
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
		// Assign the reliable seq(s) NOW (finalize time), in build
		// order, and patch them into their placeholders. The session
		// counter is only consumed here — so a packet that throws
		// during construction (before reaching this point) burns no
		// seq and leaves no gap. Must run before super.getDatagramPackets()
		// because the base writes the outer 0x13 counter from the
		// session counter's CURRENT value, which (retail-faithfully)
		// equals the LAST reliable seq in this datagram.
		if (!seqsAssigned) {
			for (int off : seqOffsets) {
				int seq = pl.getUdpConnection()
						.incandgetSessionCounter();
				buf[off]     = (byte) (seq & 0xFF);
				buf[off + 1] = (byte) ((seq >> 8) & 0xFF);
			}
			seqsAssigned = true;
		}

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
