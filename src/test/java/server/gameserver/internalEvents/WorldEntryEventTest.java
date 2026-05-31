package server.gameserver.internalEvents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.tools.PriorityList;
import server.tools.Timer;

/**
 * Smoke test for {@link WorldEntryEvent}.
 *
 * The event delegates to {@link Player#send} which, in production, writes to
 * a real {@code DatagramSocket}. In the fixture that socket is not bound, so
 * {@code send} will silently swallow {@link java.io.IOException}. We just
 * verify that the event can be executed end-to-end without throwing, and that
 * it reports a sensible {@code eventTime}.
 */
public class WorldEntryEventTest {

    @Test
    public void eventIsScheduledAfterCreation() {
        // Timer.getRealtime() is a static field updated by the Timer background
        // thread. In unit tests the thread isn't running, so getRealtime()
        // may return 0. We just verify that the schedule-offset is applied
        // on top of whatever base time is in effect.
        long base = Timer.getRealtime();
        WorldEntryEvent evt = new WorldEntryEvent();
        assertTrue("event scheduled before base time",
                evt.getEventTime() >= base + WorldEntryEvent.START_DELAY_MS);
        assertTrue("event scheduled too far in future",
                evt.getEventTime() <= base + WorldEntryEvent.START_DELAY_MS + 5000);
    }

    @Test
    public void executeDoesNotThrowOnValidPlayer() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        // Zone is null in the fixture, so the zone-broadcast sub-steps are
        // skipped. Still, this exercises all the packet builders.
        WorldEntryEvent evt = new WorldEntryEvent(0);
        assertNotNull(evt);
        try {
            evt.execute(pl);
        } catch (Throwable t) {
            throw new AssertionError("WorldEntryEvent.execute threw: " + t, t);
        }
    }

    @Test
    public void executeStartsAllThreeHeartbeatsOnFirstLogin()
            throws Exception {
        // Regression test for the SYNCHRONIZING-overlay hang fixed
        // 2026-05-09. WorldEntryEvent must schedule the three S→C
        // heartbeats (TimeSync, PoolStatus, ZoneState) immediately
        // after the burst, NOT defer them to zone-handoff. Sessions
        // without a zone-handoff (the user's reported case) would
        // otherwise never see TimeSync streaming and the client's
        // state-machine would never advance past SYNCHRONIZING.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        new WorldEntryEvent(0).execute(pl);

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        // PriorityList isn't Iterable — drain it via removeFirst()
        // and capture each event's class name.
        Set<String> scheduled = new HashSet<>();
        while (!queue.isEmpty()) {
            scheduled.add(
                queue.removeFirst().getClass().getSimpleName());
        }

        assertTrue("TimeSyncHeartbeatEvent must be scheduled, "
                + "got: " + scheduled,
                scheduled.contains("TimeSyncHeartbeatEvent"));
        assertTrue("PoolStatusHeartbeat must be scheduled, "
                + "got: " + scheduled,
                scheduled.contains("PoolStatusHeartbeat"));
        assertTrue("ZoneStateHeartbeat must be scheduled, "
                + "got: " + scheduled,
                scheduled.contains("ZoneStateHeartbeat"));
        assertTrue("TcpKeepaliveEvent must remain scheduled, "
                + "got: " + scheduled,
                scheduled.contains("TcpKeepaliveEvent"));
    }

    /**
     * Task #174 functional test. A city↔city walk-cross commits a
     * destination worldId &lt; 2001 and (via SZoning1Confirm) sets the
     * city self-position suppression flag. The world-entry burst must
     * then send NO self {@code PlayerPositionUpdate} (0x03/0x1b) — this
     * is exactly what retail does: in
     * RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT the verbose self-position
     * (0x1b 01000000 03 [XYZ]) appears only in the initial plaza_p1
     * login session, and ZERO times in the plaza_p3 or pepper_p1 cross
     * sessions. Pushing the stale source-zone self-position is the
     * "spawn reset to map centre" bug. After the burst the single-shot
     * flag must be consumed (cleared) so a later non-cross re-entry
     * still gets its authoritative self-position.
     */
    @Test
    public void cityCrossBurstSuppressesSelfPositionAndClearsFlag() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        server.testtools.CapturingUDPConnection udp =
                server.testtools.CapturingUDPConnection.replaceOn(pl);

        // Simulate the SZoning1Confirm commit for a city destination
        // (plaza_p3 = 101, retail-proven city sector).
        assertTrue(server.gameserver.ZoneBoundaries
                .isIndexedCitySector(101));
        pl.setPendingCityCrossSelfPosSuppress(true);

        new WorldEntryEvent(0).execute(pl);

        long selfPos = udp.received().stream()
                .filter(p -> p instanceof
                    server.gameserver.packets.server_udp
                        .PlayerPositionUpdate)
                .count();
        assertTrue("city walk-cross burst must send NO self"
                + " PlayerPositionUpdate (retail sends none); got "
                + selfPos, selfPos == 0);

        assertTrue("single-shot suppression flag must be consumed"
                + " (cleared) after the burst",
                !pl.isPendingCityCrossSelfPosSuppress());
    }

    /**
     * Counterpart to the city case: a normal (non-city-cross) world
     * entry — fresh login or wasteland/outdoor cross, flag never set —
     * MUST still send the authoritative self {@code PlayerPositionUpdate}.
     * This guards against the city fix regressing the known-good
     * always-send behaviour for everything else.
     */
    @Test
    public void normalEntryStillSendsSelfPosition() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        server.testtools.CapturingUDPConnection udp =
                server.testtools.CapturingUDPConnection.replaceOn(pl);

        // Flag deliberately NOT set (fresh login / outdoor cross).
        assertTrue(!pl.isPendingCityCrossSelfPosSuppress());

        new WorldEntryEvent(0).execute(pl);

        long selfPos = udp.received().stream()
                .filter(p -> p instanceof
                    server.gameserver.packets.server_udp
                        .PlayerPositionUpdate)
                .count();
        assertTrue("normal world entry must send the self"
                + " PlayerPositionUpdate (known-good fallback); got "
                + selfPos, selfPos >= 1);
    }

    /**
     * Task #202 retail-faithful semantics (user-confirmed 2026-05-19):
     * logout-while-dead must PERSIST as dead on next login — the
     * client re-shows the respawn overlay and revive is gated on
     * genrep selection (#203 / #210). {@link
     * WorldEntryEvent#rehydratePool} therefore does NOT touch
     * {@code cur≤0}; only persistence-corruption cases ({@code
     * max≤0}, {@code cur>max}) are repaired.
     */
    @Test
    public void rehydratePoolLeavesDeadCurAlone() {
        int[] cur = {0};
        int[] max = {300};
        WorldEntryEvent.rehydratePool("HP", cur[0], max[0],
                v -> cur[0] = v, v -> max[0] = v);
        assertTrue("dead cur=0 must persist (retail-faithful);"
                + " got cur=" + cur[0], cur[0] == 0);
        assertTrue("max must remain untouched when valid; got "
                + max[0], max[0] == 300);
    }

    @Test
    public void rehydratePoolRepairsZeroMaxButLeavesCur() {
        int[] cur = {0};
        int[] max = {0};
        WorldEntryEvent.rehydratePool("PSI", cur[0], max[0],
                v -> cur[0] = v, v -> max[0] = v);
        // FALLBACK_MAX = 100 (matches PlayerCharacter ctor default).
        // Corrupted max=0 is a legacy/schema repair, not a death
        // state — fix max so the HUD is playable.
        assertTrue("zero max must default to 100; got " + max[0],
                max[0] == 100);
        // BUT cur=0 must NOT be touched: retail keeps the player
        // dead until they genrep. The login burst will re-emit
        // PlayerDeath instead.
        assertTrue("zero cur must NOT be auto-restored;"
                + " got " + cur[0], cur[0] == 0);
    }

    @Test
    public void rehydratePoolClampsCurAboveMax() {
        int[] cur = {500};
        int[] max = {300};
        WorldEntryEvent.rehydratePool("STA", cur[0], max[0],
                v -> cur[0] = v, v -> max[0] = v);
        assertTrue("cur>max must be clamped to max; got " + cur[0],
                cur[0] == 300);
    }

    @Test
    public void rehydratePoolLeavesHealthyValuesAlone() {
        int[] cur = {180};
        int[] max = {250};
        WorldEntryEvent.rehydratePool("HP", cur[0], max[0],
                v -> cur[0] = v, v -> max[0] = v);
        assertTrue("healthy cur must not change; got " + cur[0],
                cur[0] == 180);
        assertTrue("healthy max must not change; got " + max[0],
                max[0] == 250);
    }

    /**
     * Task #210 wiring check: a character persisted dead (HP=0)
     * must trigger a {@link server.gameserver.packets.server_udp
     * .PlayerDeath} emit during the world-entry burst, so the
     * client re-paints the respawn overlay on re-login.
     */
    @Test
    public void persistedDeadCharacterReceivesPlayerDeathOnLogin() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        server.testtools.CapturingUDPConnection udp =
                server.testtools.CapturingUDPConnection.replaceOn(pl);

        // Force the persisted-dead state: HP=0, max intact.
        pl.getCharacter().setHealth(0);
        pl.getCharacter().setMaxHealth(300);

        new WorldEntryEvent(0).execute(pl);

        long deathPackets = udp.received().stream()
                .filter(p -> p instanceof
                    server.gameserver.packets.server_udp.PlayerDeath)
                .count();
        assertTrue("dead login must emit exactly one PlayerDeath"
                + " (re-paints respawn overlay); got " + deathPackets,
                deathPackets == 1);
    }

    /**
     * Counterpart: a healthy login (HP > 0) must NOT receive a
     * spurious {@link server.gameserver.packets.server_udp.PlayerDeath}.
     * Guards against regressing the death re-emit into "every login
     * shows the death screen for a frame".
     */
    @Test
    public void healthyCharacterReceivesNoPlayerDeathOnLogin() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        server.testtools.CapturingUDPConnection udp =
                server.testtools.CapturingUDPConnection.replaceOn(pl);

        pl.getCharacter().setHealth(180);
        pl.getCharacter().setMaxHealth(300);

        new WorldEntryEvent(0).execute(pl);

        long deathPackets = udp.received().stream()
                .filter(p -> p instanceof
                    server.gameserver.packets.server_udp.PlayerDeath)
                .count();
        assertTrue("healthy login must NOT emit PlayerDeath;"
                + " got " + deathPackets, deathPackets == 0);
    }

    /**
     * Root-cause fix (2026-05-31, reverse-engi2): the LOGIN/world-entry
     * burst's FIRST reliable 0x13/0x03 packet must be REAL WINDOWED DATA
     * (CharInfo, sub-op 0x2c) at seq=1 — NOT a sub-op 0x08 control packet.
     *
     * <p>A prior fix emitted a ZoningEnd (0x08) primer here as seq=1.
     * Live apartment-idle wire diff proved it CAUSED the NAK storm: the
     * client received the 0x08 (cipher + delivery verified byte-exact)
     * but could not advance its reliable-window BASE past a control op it
     * does not treat as windowed data, so it NAK-stormed seqs 1,2,3,…
     * forever (raw 0x01 ×36/4s) and the server flooded 0x02 retransmits.
     * Decoded retail's login reliable DATA stream: it is all normal
     * windowed ops and NEVER contains a server-emitted 0x08 (0x08 is the
     * C→S reliable-ACK op). So seq=1 must be windowed data; CharInfo is
     * what retail sends first. This test guards against re-introducing a
     * non-windowed control op at seq=1.
     */
    @Test
    public void firstReliableOnLoginIsWindowedDataNotControlOp() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        server.testtools.CapturingUDPConnection udp =
                server.testtools.CapturingUDPConnection.replaceOn(pl);

        // Fresh login: the world-entry burst begins on counter=0
        // (pre-increment yields the first reliable seq=1).
        assertTrue("login burst must begin on a fresh counter",
                pl.getUdpConnection().getSessionCounter() == 0);

        new WorldEntryEvent(0).execute(pl);

        // Walk the captured plaintext datagrams in emit order and find
        // the FIRST reliable 0x13/0x03 sub-packet. Its [seq LE2] must be
        // 1 and its sub-op (byte after seq) must NOT be 0x08 (ZoningEnd).
        int[] firstSeq = {-1};
        int[] firstOp = {-1};
        for (byte[] dg : udp.rawBytes()) {
            if (dg.length < 8) continue;
            // Reliable wire: [0x13][ctr LE2][ctr+sk LE2] then
            // ([subLen LE2][0x03][seq LE2][op]...)+
            if ((dg[0] & 0xFF) != 0x13) continue;
            int i = 5; // past [0x13][ctr LE2][ctr+sk LE2]
            boolean found = false;
            while (i + 2 <= dg.length) {
                int subLen = (dg[i] & 0xFF) | ((dg[i + 1] & 0xFF) << 8);
                i += 2;
                if (subLen <= 0 || i + subLen > dg.length) break;
                if (subLen >= 4 && (dg[i] & 0xFF) == 0x03) {
                    firstSeq[0] = (dg[i + 1] & 0xFF)
                            | ((dg[i + 2] & 0xFF) << 8);
                    firstOp[0] = dg[i + 3] & 0xFF;
                    found = true;
                    break;
                }
                i += subLen;
            }
            if (found) break;
        }

        assertTrue("world-entry must emit at least one reliable "
                + "0x13/0x03 packet", firstSeq[0] >= 0);
        assertEquals("first reliable on login must claim seq=1",
                1, firstSeq[0]);
        assertNotEquals("first reliable on login (seq=1) must be real "
                + "windowed DATA, NOT the 0x08 control op — a server-"
                + "emitted 0x08 at seq=1 stalls the client window base "
                + "and triggers the NAK storm (retail never does this)",
                0x08, firstOp[0]);
        assertEquals("retail sends CharInfo (0x2c) as the first login "
                + "reliable; Ceres must match so the client windows it "
                + "and advances", 0x2c, firstOp[0]);
    }

    /**
     * Extract every S→C reliable {@code 0x03} sub-packet seq from a
     * list of captured plaintext datagrams, in wire order. Walks the
     * {@code [0x13][ctr LE2][ctr+sk LE2]([subLen LE2][sub])+}
     * framing and collects {@code [seq LE2]} from each {@code 0x03}
     * sub-packet. {@code 0x02} (independent retransmit-channel
     * counter) and raw sub-packets are ignored.
     */
    private static java.util.List<Integer> reliableSeqs(
            java.util.List<byte[]> datagrams) {
        java.util.List<Integer> seqs = new java.util.ArrayList<>();
        for (byte[] dg : datagrams) {
            if (dg.length < 5 || (dg[0] & 0xFF) != 0x13) continue;
            int i = 5; // past [0x13][ctr LE2][ctr+sk LE2]
            while (i + 2 <= dg.length) {
                int subLen = (dg[i] & 0xFF) | ((dg[i + 1] & 0xFF) << 8);
                i += 2;
                if (subLen <= 0 || i + subLen > dg.length) break;
                if (subLen >= 4 && (dg[i] & 0xFF) == 0x03) {
                    seqs.add((dg[i + 1] & 0xFF)
                            | ((dg[i + 2] & 0xFF) << 8));
                }
                i += subLen;
            }
        }
        return seqs;
    }

    /**
     * Extract every reliable sub-packet seq across BOTH wrapper
     * types — {@code 0x02} (simplified-reliable init wrapper) and
     * {@code 0x03} (reliable game-state wrapper) — in wire order.
     *
     * <p>Since the root-cause fix, the {@code 0x02} init packets
     * draw their seq from the SAME unified
     * {@code udpSessionCounter} the {@code 0x03} stream uses (they
     * share the client's reliable receive-window namespace). The
     * real invariant is therefore that the union of all 0x02 and
     * 0x03 reliable seqs is contiguous and dup-free — not that the
     * 0x03-only view is (it now legitimately has gaps where 0x02
     * inits consumed seqs).
     */
    private static java.util.List<Integer> unifiedReliableSeqs(
            java.util.List<byte[]> datagrams) {
        java.util.List<Integer> seqs = new java.util.ArrayList<>();
        for (byte[] dg : datagrams) {
            if (dg.length < 5 || (dg[0] & 0xFF) != 0x13) continue;
            int i = 5; // past [0x13][ctr LE2][ctr+sk LE2]
            while (i + 2 <= dg.length) {
                int subLen = (dg[i] & 0xFF) | ((dg[i + 1] & 0xFF) << 8);
                i += 2;
                if (subLen <= 0 || i + subLen > dg.length) break;
                int wrapper = dg[i] & 0xFF;
                if (subLen >= 4 && (wrapper == 0x02 || wrapper == 0x03)) {
                    seqs.add((dg[i + 1] & 0xFF)
                            | ((dg[i + 2] & 0xFF) << 8));
                }
                i += subLen;
            }
        }
        return seqs;
    }

    /**
     * THE reliable-channel regression guard (root cause, 2026-05-31).
     *
     * <p>The reliable seq stream the world-entry burst emits MUST be
     * strictly contiguous starting at 1 — no skipped seq, no
     * duplicate. A single gap (the historical seq-2 skip when a
     * {@code PacketBuilderUDP1303} consumed a seq in its constructor
     * but never reached the wire) stalls the client's reliable receive
     * window: it NAK-floods (raw {@code 0x01}) the missing seq forever
     * and the server answers each with a retransmit — the livelock
     * diagnosed in the apartment-idle diff. Retail's stream is
     * strictly contiguous and dup-free; this asserts Ceres matches.
     *
     * <p>Since the root-cause fix, the {@code 0x02} simplified-
     * reliable init packets share the client's reliable receive-
     * window namespace with the {@code 0x03} stream and draw their
     * seq from the SAME unified counter. So the invariant holds over
     * the UNION of 0x02 and 0x03 reliable seqs — the 0x03-ONLY view
     * now legitimately has gaps (e.g. [0,5,6,...]) where the 0x02
     * inits consumed seqs 1..4. We collect both wrappers, sort, and
     * assert the union is a contiguous dup-free run from 0.
     */
    @Test
    public void worldEntryReliableSeqStreamIsContiguousNoGapsNoDups() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        server.testtools.CapturingUDPConnection udp =
                server.testtools.CapturingUDPConnection.replaceOn(pl);

        new WorldEntryEvent(0).execute(pl);

        java.util.List<Integer> seqs =
                unifiedReliableSeqs(udp.rawBytes());
        assertTrue("world-entry must emit reliable 0x02/0x03 packets",
                !seqs.isEmpty());

        // Dup check across the unified namespace (wire order).
        Set<Integer> seen = new HashSet<>();
        for (int s : seqs) {
            assertTrue("duplicate reliable seq " + s + " in unified "
                    + "0x02/0x03 stream " + seqs, seen.add(s));
        }

        // Contiguity check over the SORTED union: every reliable seq,
        // whether 0x02 or 0x03, must be unique and form 0,1,2,...
        java.util.List<Integer> sorted =
                new java.util.ArrayList<>(seqs);
        java.util.Collections.sort(sorted);
        int expected = 1;
        for (int s : sorted) {
            assertEquals("unified reliable seq stream must be "
                    + "contiguous (no gap) — expected " + expected
                    + " got " + s + " in sorted union " + sorted,
                    expected, s);
            expected++;
        }
        assertEquals("unified stream must start at seq=1", 1,
                (int) sorted.get(0));
    }

    /**
     * Gap-immunity guard for the exact mechanism that skipped seq 2.
     *
     * <p>Before the fix, {@link
     * server.networktools.PacketBuilderUDP1303} consumed a reliable
     * seq in its CONSTRUCTOR. A packet that threw before reaching the
     * wire (swallowed by {@code WorldEntryEvent.safeSend}) therefore
     * burned a seq with no {@code 0x03/[seq]} ever emitted → permanent
     * gap. The fix defers seq assignment to {@code getDatagramPackets()}
     * (finalize), so a never-finalized packet consumes nothing. This
     * test interleaves a throwing reliable between two good ones and
     * asserts the surviving seq stream is still contiguous (1,2),
     * i.e. the throwing packet left no hole.
     */
    @Test
    public void throwingReliablePacketLeavesNoSeqGap() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        server.testtools.CapturingUDPConnection udp =
                server.testtools.CapturingUDPConnection.replaceOn(pl);

        // good reliable -> seq 1
        pl.send(new server.gameserver.packets.server_udp.ChatList(pl));

        // A reliable that throws during getDatagramPackets() AFTER
        // construction — emulates a malformed-item CharInfo. With the
        // fix it must NOT consume a seq (assignment is deferred to the
        // point past where it throws). safeSend-style swallow:
        try {
            server.interfaces.ServerUDPPacket bad =
                new server.networktools.PacketBuilderUDP1303(pl) {
                    @Override
                    public java.net.DatagramPacket[] getDatagramPackets() {
                        throw new RuntimeException("simulated build failure");
                    }
                };
            pl.send(bad);
        } catch (RuntimeException ignored) {
            // WorldEntryEvent.safeSend swallows exactly this.
        }

        // good reliable -> must be seq 2 (NOT 3 — no burned seq)
        pl.send(new server.gameserver.packets.server_udp.ChatList(pl));

        java.util.List<Integer> seqs = reliableSeqs(udp.rawBytes());
        assertEquals("two good reliables surround one throwing one; "
                + "with deferred seq assignment the stream must be "
                + "[1, 2] with no burned seq, got " + seqs,
                java.util.Arrays.asList(1, 2), seqs);
    }

    @Test
    public void executeToleratesMissingCharacter() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        // Blank out the character so execute() must take the early-return path.
        try {
            java.lang.reflect.Field f = Player.class.getDeclaredField("pc");
            f.setAccessible(true);
            f.set(pl, null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }

        WorldEntryEvent evt = new WorldEntryEvent(0);
        evt.execute(pl); // must not throw
    }
}
