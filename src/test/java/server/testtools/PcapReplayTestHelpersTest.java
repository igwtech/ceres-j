package server.testtools;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for the package-private helper predicates in
 * {@link PcapReplayTest} that classify retail S→C bytes as
 * unreplicable / spare emissions for the harness skip path.
 *
 * <p>These predicates are pure: no harness state, just byte
 * inspection. Pinning them at the unit level prevents accidental
 * regression of the harness's signal-to-noise tuning.
 */
public class PcapReplayTestHelpersTest {

    // ─── isSpareUDPAlive ────────────────────────────────────────

    @Test
    public void spareUDPAlive_skipsRetail7BUDPAliveWhenCerJEmitsOther() {
        byte[] retail = new byte[]{0x04, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78};   // 7B UDPAlive
        byte[] cerj   = new byte[]{0x13, 0x01, 0x00,
                0x10, 0x20, 0x05, 0x00,
                0x03, 0x01, 0x00};        // 0x13-wrapped reliable
        assertTrue(PcapReplayTest.isSpareUDPAlive(retail, cerj));
    }

    @Test
    public void spareUDPAlive_doesNOTSkipWhenBothAreUDPAlive() {
        byte[] retail = new byte[]{0x04, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78};
        byte[] cerj   = new byte[]{0x04, 0x00, 0x00,
                (byte) 0x9a, (byte) 0xbc, (byte) 0xde,
                (byte) 0xf0};
        assertFalse("if both are UDPAlive, pair them — don't skip",
                PcapReplayTest.isSpareUDPAlive(retail, cerj));
    }

    @Test
    public void spareUDPAlive_doesNOTSkipNon7BPacket() {
        byte[] retail = new byte[]{0x04, 0x01, 0x02};   // 3B
        byte[] cerj   = new byte[]{0x0b, 0x00};
        assertFalse(PcapReplayTest.isSpareUDPAlive(retail, cerj));
    }

    // ─── isUnreplicableNpcBroadcast ────────────────────────────

    @Test
    public void unreplicableNpcBroadcast_skipsRaw1bWhenCerJDoesNotMirror() {
        byte[] retail = new byte[19];
        retail[0] = 0x1b;  // raw 0x1b position broadcast
        byte[] cerj   = new byte[]{0x0b, 0x00, 0x00, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78}; // SPing
        assertTrue(PcapReplayTest
                .isUnreplicableNpcBroadcast(retail, cerj));
    }

    @Test
    public void unreplicableNpcBroadcast_skipsReliable03_1bWhenCerJDoesNotMirror() {
        byte[] retail = new byte[]{0x03, 0x05, 0x00,
                0x1b, /* body */ 0x00, 0x00, 0x00};
        byte[] cerj   = new byte[]{0x0b, 0x00, 0x00, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78};
        assertTrue(PcapReplayTest
                .isUnreplicableNpcBroadcast(retail, cerj));
    }

    @Test
    public void unreplicableNpcBroadcast_doesNOTSkipWhenCerJEmits1bToo() {
        byte[] retail = new byte[19];
        retail[0] = 0x1b;
        byte[] cerj = new byte[19];
        cerj[0] = 0x1b;
        assertFalse("Ceres-J also emitted 0x1b — pair them",
                PcapReplayTest.isUnreplicableNpcBroadcast(retail,
                        cerj));
    }

    @Test
    public void unreplicableNpcBroadcast_skipsWhenCerJEmittedNothing() {
        byte[] retail = new byte[19];
        retail[0] = 0x1b;
        // Empty Ceres-J — interpreted as "skip retail entry".
        assertTrue(PcapReplayTest
                .isUnreplicableNpcBroadcast(retail, new byte[0]));
    }

    // ─── isUnreplicableInitBurst (NEW 2026-05-09) ───────────────

    @Test
    public void unreplicableInitBurst_skipsRetailOtherWhenCerJEmittedSPing() {
        // Ceres-J emitted exactly a 9B raw 0x0b SPing reply; retail
        // has e.g. a 0x02 init-burst packet next in queue → skip
        // the retail entry and re-pair against retail's SPing.
        byte[] cerj = new byte[]{0x0b, 0x00, 0x00, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78};
        byte[] retailInitBurst = new byte[]{0x02, 0x01, 0x00,
                0x2e, 0x01, 0x00, 0x00, 0x00};   // 0x02/0x2e
        byte[] retailReliable  = new byte[]{0x03, 0x13, 0x00,
                0x23, 0x20, 0x00, (byte) 0x84,
                0x00};                            // 0x03/0x23
        byte[] retailChatList  = new byte[]{0x03, 0x15, 0x00,
                0x33, (byte) 0xff, 0x00};        // 0x03/0x33
        assertTrue(PcapReplayTest.isUnreplicableInitBurst(
                retailInitBurst, cerj));
        assertTrue(PcapReplayTest.isUnreplicableInitBurst(
                retailReliable, cerj));
        assertTrue(PcapReplayTest.isUnreplicableInitBurst(
                retailChatList, cerj));
    }

    @Test
    public void unreplicableInitBurst_doesNOTSkipWhenRetailIsAlsoSPing() {
        // Both sides emitted a 9B raw 0x0b SPing reply — pair them.
        byte[] cerj   = new byte[]{0x0b, 0x00, 0x00, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78};
        byte[] retail = new byte[]{0x0b, 0x00, 0x00, 0x00, 0x00,
                (byte) 0xa0, (byte) 0xb0, (byte) 0xc0,
                (byte) 0xd0};
        assertFalse(PcapReplayTest.isUnreplicableInitBurst(
                retail, cerj));
    }

    @Test
    public void unreplicableInitBurst_doesNOTSkipWhenCerJDidNotEmitSPing() {
        // Ceres-J emitted a 0x13-wrapped reliable. Don't engage
        // this skip — leave it to other heuristics.
        byte[] cerj = new byte[]{0x13, 0x01, 0x00, 0x10, 0x20,
                0x05, 0x00, 0x03, 0x01, 0x00};
        byte[] retail = new byte[]{0x02, 0x01, 0x00, 0x2e};
        assertFalse(PcapReplayTest.isUnreplicableInitBurst(
                retail, cerj));
    }

    @Test
    public void unreplicableInitBurst_doesNOTSkipWhenRetailIsEmpty() {
        byte[] cerj = new byte[]{0x0b, 0x00, 0x00, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78};
        assertFalse(PcapReplayTest.isUnreplicableInitBurst(
                new byte[0], cerj));
    }
}
