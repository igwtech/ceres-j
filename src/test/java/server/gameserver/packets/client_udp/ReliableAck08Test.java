package server.gameserver.packets.client_udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.state.ClientStateMachine;

/**
 * Functional tests for {@link ReliableAck08} — the C→S 0x03/0x08
 * explicit reliable-ACK handler. Verifies wire decode (off-by-one
 * corrected) and that the FSM's last-confirmed-seq pointer is
 * advanced. Two malformed-frame guards confirm a short body is
 * logged as "no-ack" without throwing.
 *
 * <p>Wire layout (6 bytes):
 * <pre>
 *   [0]    0x03      reliable channel
 *   [1..2] own_seq   LE16 (this packet's own seq — not the ack)
 *   [3]    0x08      ack sub-opcode
 *   [4..5] target-1  LE16 (acked seq, encoded as (target-1) mod 2¹⁶)
 * </pre>
 */
public class ReliableAck08Test {

    /** Construct a complete 6-byte ack frame with the given
     *  encoded {@code wireSeq} (i.e. {@code target - 1}). */
    private static byte[] frame(int ownSeq, int wireSeq) {
        byte[] b = new byte[6];
        b[0] = 0x03;
        b[1] = (byte) (ownSeq & 0xff);
        b[2] = (byte) ((ownSeq >> 8) & 0xff);
        b[3] = 0x08;
        b[4] = (byte) (wireSeq & 0xff);
        b[5] = (byte) ((wireSeq >> 8) & 0xff);
        return b;
    }

    @Test
    public void ackedSeqAddsOneToWireValue() {
        ReliableAck08 ack = new ReliableAck08(frame(0x1234, 41));
        assertEquals(42, ack.ackedSeq());
    }

    @Test
    public void ackedSeqHandlesWireWraparound() {
        // wire value 0xffff encodes acked seq 0 (after +1 mod 2¹⁶).
        ReliableAck08 ack = new ReliableAck08(frame(0xabcd, 0xffff));
        assertEquals(0, ack.ackedSeq());
    }

    @Test
    public void shortBufferReturnsNegativeOne() {
        ReliableAck08 ack = new ReliableAck08(new byte[]{0x03, 0x00});
        assertEquals(-1, ack.ackedSeq());
    }

    @Test
    public void executeAdvancesFsmPointer() {
        Player pl = PacketTestFixture.newPlayer();
        assertNotNull("PacketTestFixture must wire an FSM",
                pl.getStateMachine());

        new ReliableAck08(frame(0x1000, 99)).execute(pl);

        // wire-99 + 1 = acked seq 100
        assertEquals(100, pl.getStateMachine()
                .lastConfirmedReliableSeq());
    }

    @Test
    public void executeIsNoOpOnShortBuffer() {
        Player pl = PacketTestFixture.newPlayer();
        new ReliableAck08(new byte[]{0x03, 0x00}).execute(pl);
        // last seq stays at the FSM default of -1.
        assertEquals(-1, pl.getStateMachine()
                .lastConfirmedReliableSeq());
    }

    @Test
    public void executeIsNoOpOnNullPlayer() {
        // Belt-and-suspenders: the dispatcher shouldn't pass null,
        // but if it does the handler must not NPE.
        new ReliableAck08(frame(0, 0)).execute((Player) null);
    }

    @Test
    public void successiveAcksMonotonicallyAdvancePointer() {
        // Three acks in a row, simulating a normal session — each
        // advances the FSM pointer.
        Player pl = PacketTestFixture.newPlayer();
        ClientStateMachine fsm = pl.getStateMachine();
        new ReliableAck08(frame(0x0001, 9)).execute(pl);   // ack 10
        assertEquals(10, fsm.lastConfirmedReliableSeq());
        new ReliableAck08(frame(0x0002, 19)).execute(pl);  // ack 20
        assertEquals(20, fsm.lastConfirmedReliableSeq());
        new ReliableAck08(frame(0x0003, 49)).execute(pl);  // ack 50
        assertEquals(50, fsm.lastConfirmedReliableSeq());
    }
}
