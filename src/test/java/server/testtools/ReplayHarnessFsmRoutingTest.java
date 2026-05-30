package server.testtools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import server.gameserver.packets.client_udp.ReliableAck08;
import server.gameserver.state.ClientState;
import server.gameserver.state.ClientStateMachine;

/**
 * End-to-end routing test for the FSM observation hooks added in
 * tasks #239 + #243 (task #246).
 *
 * <p>{@link ClientStateMachineTest} tests the FSM as a value
 * object; {@link
 * server.gameserver.state.ClientStateMachineIntegrationTest}
 * tests the trajectory via direct {@code fsm.transition(...)}
 * calls. This test verifies that a real C→S byte sequence
 * driven through {@link ReplayHarness} — i.e. through the full
 * {@code GamePacketReaderUDP.decodesub13} dispatcher — actually
 * lands at the FSM hooks. Catches regressions where someone
 * removes a dispatcher route + the unit tests still pass
 * because they bypass the dispatcher.
 *
 * <p>The flagship route validated here: {@code 0x03/0x08
 * ReliableAck08} (added in #239 to the {@code case 0x03 →
 * switch} table) decodes the wire-encoded ack-seq, off-by-one
 * corrects it, and pushes the result into the FSM's ack
 * pointer.
 */
public class ReplayHarnessFsmRoutingTest {

    /** Build a 0x13-inner sub-packet shaped like the wire
     *  {@code 0x03/0x08 ReliableAck08} frame:
     *  <pre>
     *    [0x03][own_seq2][0x08][acked-1 LE2]   (6 bytes)
     *  </pre> */
    private static byte[] ack08Body(int ownSeq, int wireSeq) {
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
    public void freshHarnessPlayerHasFsmAtPreLogin() {
        // Phase-1 contract from #239: every Player constructed
        // via PacketTestFixture (which ReplayHarness uses)
        // starts with a FSM in PRE_LOGIN. Without this baseline
        // the trajectory tests below mean nothing.
        ReplayHarness h = new ReplayHarness();
        assertNotNull("harness Player must have an FSM",
                h.player().getStateMachine());
        assertEquals("FSM starts in PRE_LOGIN",
                ClientState.PRE_LOGIN,
                h.player().getStateMachine().getState());
        assertEquals("no acks observed yet",
                -1,
                h.player().getStateMachine()
                        .lastConfirmedReliableSeq());
    }

    @Test
    public void ack08FrameDrivenThroughDispatcherAdvancesFsmSeq() {
        // The KEY routing assertion of task #246: the new
        // case 0x03 → case 0x08 in GamePacketReaderUDP.decodesub13
        // really does route the wire bytes to ReliableAck08, whose
        // execute() advances the FSM. If a future refactor moves
        // the case-0x08 branch (or accidentally swallows it as a
        // fall-through unrecognised sub-packet), this test fails.
        ReplayHarness h = new ReplayHarness();
        ClientStateMachine fsm = h.player().getStateMachine();
        assertEquals("baseline", -1, fsm.lastConfirmedReliableSeq());

        ReplayHarness.DriveResult r = h.drive(ack08Body(0x0001, 41));

        assertTrue("ack08 frame must be recognised by the decoder",
                r.wasRecognised());
        assertTrue("decoded class must be ReliableAck08",
                r.decoded instanceof ReliableAck08);
        // wire-encoded 41 → real acked seq 42 (off-by-one corrected)
        assertEquals("FSM ack pointer must advance to 42",
                42, fsm.lastConfirmedReliableSeq());
    }

    @Test
    public void successiveAck08FramesAdvanceFsmMonotonically() {
        // Three acks back-to-back via the dispatcher; FSM pointer
        // must walk forward each time (proves the routing isn't
        // a one-shot, isn't holding stale state).
        ReplayHarness h = new ReplayHarness();
        ClientStateMachine fsm = h.player().getStateMachine();

        h.drive(ack08Body(0x0001, 9));   // → ack seq 10
        assertEquals(10, fsm.lastConfirmedReliableSeq());
        h.drive(ack08Body(0x0002, 19));  // → ack seq 20
        assertEquals(20, fsm.lastConfirmedReliableSeq());
        h.drive(ack08Body(0x0003, 99));  // → ack seq 100
        assertEquals(100, fsm.lastConfirmedReliableSeq());

        // Three drive() calls = three history entries.
        assertEquals(3, h.history().size());
    }

    @Test
    public void onceAckedCallbackFiresWhenWireAckArrives() {
        // The Phase-2 (#242) primitive: register a callback for
        // a specific seq, then verify it fires when an ack08 frame
        // for that seq comes through the dispatcher. This is what
        // PortalCrossCommitEvent's gate will hang on — proving it
        // works end-to-end here means Phase 2 wiring will Just
        // Work when the seq-tracking infra is added.
        ReplayHarness h = new ReplayHarness();
        ClientStateMachine fsm = h.player().getStateMachine();

        boolean[] fired = new boolean[1];
        fsm.onceAcked(50, () -> fired[0] = true);
        assertEquals(1, fsm.pendingCallbackCount());

        // Drive an ack BELOW 50 — callback must NOT fire yet.
        h.drive(ack08Body(0x0001, 20)); // ack 21
        assertEquals(21, fsm.lastConfirmedReliableSeq());
        assertEquals("seq 21 < 50 — gate must hold",
                false, fired[0]);

        // Drive the unlocking ack.
        h.drive(ack08Body(0x0002, 49)); // ack 50
        assertEquals(50, fsm.lastConfirmedReliableSeq());
        assertEquals("seq 50 ≥ 50 — gate fires",
                true, fired[0]);
        assertEquals("callback dequeued",
                0, fsm.pendingCallbackCount());
    }
}
