package server.networktools;

import server.gameserver.Player;

/**
 * Builds a {@code 0x13} gamedata packet with a {@code 0x02}
 * "simplified reliable" wrapper.
 *
 * <pre>
 *   [0x02][seq_lo][seq_hi][inner_sub_type][inner_data...]
 * </pre>
 *
 * <p>The {@code 0x02} wrapper is what retail uses to <strong>answer
 * a client retransmit request</strong>: when the client sends a
 * {@code C->S 0x01 [seq]} ack/retransmit-request, the server
 * re-sends the missing reliable as {@code S->C 0x02 [seq][body]}
 * carrying <em>the very same seq the client asked for</em> — that
 * is how the client maps the resent body back onto the gap in its
 * reliable receive window.
 *
 * <p>Decoded 2026-05-15 from {@code RETAIL_PLAZA_CROSSZONE}:
 * <pre>
 *   C-&gt;S  13 .. 01 0100        ask retransmit of seq 0x0001
 *   S-&gt;C  13 .. 02 0100 2e ..  resend seq 0x0001 body (sub-op 0x2e)
 * </pre>
 * The seq in the {@code 0x02} reply is always identical to the
 * requested seq, never a free-running counter.
 *
 * <p><strong>Historical bug:</strong> this builder used a
 * {@code static int seq} that incremented on every construction
 * (shared across all players and all retransmits). The client
 * received the resent body under a wrong, ever-changing seq, could
 * never close the gap, and flooded {@code 0x01} retransmit
 * requests forever — the "Synchronizing" overlay that blocked the
 * plaza_p1 → plaza_p3 zone-cross. Callers must now pass the exact
 * seq being retransmitted.
 */
public class PacketBuilderUDP1302 extends PacketBuilderUDP13 {

    /** Init / fire-and-forget constructor.
     *
     *  <p><b>Root-cause fix 2026-05-31 (reverse-engi2):</b> the 0x02
     *  "simplified reliable" wrapper shares the CLIENT's reliable
     *  receive-window seq namespace with the 0x03 reliable stream — the
     *  client commits a 0x02 packet by its seq exactly like a 0x03.
     *  This builder used a PRIVATE {@code static int seq = 1} counter,
     *  independent of {@code incandgetSessionCounter()} that 0x03 uses.
     *  At login the 0x02 init packets (InitWeather/Soullight/InfoResponse/
     *  …) took seqs 1,2,3,4 while the 0x03 stream ALSO emitted seqs
     *  2,3,4 — a COLLISION. The 0x02 lands first, the client commits
     *  seqs 2/3/4 with the init bodies, then sees the genuine 0x03/seq
     *  2,3,4 (CharSys/Config/timestamp state) as already-received
     *  DUPLICATES and drops them — the real state never commits, and the
     *  client re-requests forever (the apartment-idle raw-0x01 storm /
     *  0x02 retransmit flood; byte-proven by trace diff vs retail, which
     *  emits ZERO 0x02 sub-packets in the login burst). Drawing the seq
     *  from the SAME unified counter guarantees no seq is ever issued to
     *  both a 0x02 and a 0x03 body. */
    public PacketBuilderUDP1302(Player pl) {
        super(pl);
        write(2);                 // 0x02 wrapper (simplified reliable)
        writeShort(pl.getUdpConnection().incandgetSessionCounter() & 0xFFFF);
    }

    /**
     * Retransmit-reply constructor.
     *
     * @param pl  the target player session
     * @param seq the reliable sequence number this 0x02 packet
     *            carries — for a retransmit reply it MUST equal the
     *            seq the client requested via {@code 0x01 [seq]},
     *            so the client can map the resent body onto the gap
     *            in its reliable receive window.
     */
    public PacketBuilderUDP1302(Player pl, int seq) {
        super(pl);
        write(2);                 // 0x02 wrapper (simplified reliable)
        writeShort(seq & 0xFFFF);
    }
}
