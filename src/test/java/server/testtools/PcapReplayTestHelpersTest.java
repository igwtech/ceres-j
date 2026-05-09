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
    public void unreplicableNpcBroadcast_skipsRaw1bWhenCerJEmitsWrappedReliable() {
        // Raw 0x1b (19B) and 0x13-wrapped reliable 0x03/0x1b are
        // DIFFERENT wire shapes — they can never byte-match. Pre-fix
        // bug (resolved 2026-05-09): the predicate accepted any 0x1b
        // inside cerJBytes (including wrapped reliable), pairing
        // them against retail's raw 0x1b → manufactured a divergence.
        byte[] retailRaw1b = new byte[19];
        retailRaw1b[0] = 0x1b;
        byte[] cerJWrappedRel = new byte[]{0x13, 0x06, 0x00, 0x3f,
                0x01, 0x0f, 0x00, 0x03, 0x06, 0x00, 0x1b,
                (byte) 0xed, 0x03, 0x00, 0x00};
        assertTrue("retail raw 0x1b should skip when Ceres-J emits "
                + "wrapped reliable (different shape)",
                PcapReplayTest.isUnreplicableNpcBroadcast(
                        retailRaw1b, cerJWrappedRel));
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

    // ─── isUnreplicableEntityState (NEW 2026-05-09) ─────────────

    @Test
    public void entityState_skipsRetail03_1fGameplayWhenCerJEmitsOther() {
        // Retail emits 0x03/0x1f gameplay tags (NPC AI, weapon-fire,
        // etc.) which require entity state Ceres-J's test fixture
        // doesn't have. Skip when Ceres-J emits a different reliable.
        byte[] retail = new byte[]{0x03, (byte) 0xb4, 0x01, 0x1f,
                0x01, 0x00, 0x25, 0x23, 0x31};
        byte[] cerJ   = new byte[]{0x03, 0x0a, 0x00, 0x1b,  // 0x03/0x1b
                0x00, (byte) 0xec, 0x00, 0x00, 0x20};
        assertTrue(PcapReplayTest
                .isUnreplicableEntityState(retail, cerJ));
    }

    @Test
    public void entityState_skipsRetail03_2dWhenCerJEmitsOther() {
        byte[] retail = new byte[]{0x03, 0x05, 0x00, 0x2d, 0x10,
                0x20, 0x30, 0x40};
        byte[] cerJ   = new byte[]{0x0b, 0x00, 0x00, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78};   // SPing
        assertTrue(PcapReplayTest
                .isUnreplicableEntityState(retail, cerJ));
    }

    @Test
    public void entityState_skipsRetailRaw1fWhenCerJEmitsOther() {
        byte[] retail = new byte[]{0x1f, 0x01, 0x00, 0x25, 0x23};
        byte[] cerJ   = new byte[]{0x03, 0x0a, 0x00, 0x0d};  // 0x03/0x0d
        assertTrue(PcapReplayTest
                .isUnreplicableEntityState(retail, cerJ));
    }

    @Test
    public void entityState_doesNOTSkipWhenCerJEmitsSameSubTag() {
        // Both emit 0x03/0x1f → pair them, don't skip.
        byte[] retail = new byte[]{0x03, (byte) 0xb4, 0x01, 0x1f,
                0x01};
        byte[] cerJ   = new byte[]{0x03, 0x0a, 0x00, 0x1f, 0x02};
        assertFalse(PcapReplayTest
                .isUnreplicableEntityState(retail, cerJ));
    }

    @Test
    public void entityState_doesNOTSkipNonTargetSubTags() {
        // 0x03/0x33 ChatList is not in the target set — don't skip.
        byte[] retail = new byte[]{0x03, 0x15, 0x00, 0x33,
                (byte) 0xff, 0x00};
        byte[] cerJ   = new byte[]{0x0b, 0x00, 0x00, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78};
        assertFalse(PcapReplayTest
                .isUnreplicableEntityState(retail, cerJ));
    }

    @Test
    public void entityState_skipsRaw0x20EntityBroadcast() {
        // Raw 0x20 top-level entity broadcast (NPC spawn / position).
        byte[] retail = new byte[]{0x20, (byte) 0xfb, 0x03,
                (byte) 0x95, 0x7c, 0x00, (byte) 0x80, 0x29,
                0x7f, 0x35, 0x00, 0x00, 0x00};
        byte[] cerJ = new byte[]{0x0b, 0x00, 0x00, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78};   // SPing
        assertTrue(PcapReplayTest
                .isUnreplicableEntityState(retail, cerJ));
    }

    @Test
    public void entityState_skipsRaw0x3cEntityBroadcast() {
        byte[] retail = new byte[]{0x3c, 0x10, 0x20, 0x30};
        byte[] cerJ = new byte[]{0x03, 0x0a, 0x00, 0x0d};
        assertTrue(PcapReplayTest
                .isUnreplicableEntityState(retail, cerJ));
    }

    @Test
    public void entityState_skips03_2fUpdateModel() {
        byte[] retail = new byte[]{0x03, 0x05, 0x00, 0x2f, 0x01};
        byte[] cerJ = new byte[]{0x03, 0x0a, 0x00, 0x23, 0x20};
        assertTrue(PcapReplayTest
                .isUnreplicableEntityState(retail, cerJ));
    }

    @Test
    public void entityState_handles0x13WrappedCerJEmissions() {
        // Ceres-J's 0x13-wrapped 0x03/0x1f reliable should be
        // unwrapped and matched against retail's 0x03/0x1f.
        byte[] retail = new byte[]{0x03, 0x05, 0x00, 0x1f, 0x01};
        byte[] cerJWrapped = new byte[]{0x13, 0x01, 0x00, 0x10,
                0x20, 0x05, 0x00, 0x03, 0x0a, 0x00, 0x1f, 0x02};
        assertFalse("0x13-wrapped 0x03/0x1f should NOT skip — pair "
                + "with retail's 0x03/0x1f",
                PcapReplayTest.isUnreplicableEntityState(retail,
                        cerJWrapped));
    }
}
