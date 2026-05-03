package server.gameserver.npc;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.WorldMessageBus;

/**
 * Unit + functional tests for {@link MobManager}.
 *
 * <p>The state map and broadcast fan-out are tested in isolation
 * using the test-seam strategies on {@link MobManager}. Bus
 * integration tests post a {@link MobStateChangeIntent} through a
 * {@link WorldMessageBus} that has the manager's handler installed
 * and verify the captured packets.
 */
public class MobManagerTest {

    static final class CapturedSend {
        final Player recipient;
        final MobStateChangeIntent intent;
        CapturedSend(Player r, MobStateChangeIntent i) {
            this.recipient = r;
            this.intent = i;
        }
    }

    private List<CapturedSend> captured;

    private static Player makePlayer(int uid, String name) {
        Account acc = new Account(uid);
        acc.setUsername("u" + uid);
        Player pl = new Player(acc);
        PlayerCharacter pc = new PlayerCharacter();
        pc.setName(name);
        pc.setMisc(PlayerCharacter.MISC_ID, uid);
        try {
            Field f = Player.class.getDeclaredField("pc");
            f.setAccessible(true);
            f.set(pl, pc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return pl;
    }

    @Before
    public void setUp() {
        captured = new ArrayList<>();
        MobManager.resetForTesting();
        MobManager.setPacketSinkForTesting(
                (r, i) -> captured.add(new CapturedSend(r, i)));
    }

    @After
    public void tearDown() {
        MobManager.resetForTesting();
    }

    // ─── State map tests ────────────────────────────────────────────

    @Test
    public void firstSetStateRecordsAndPostsIntent() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        MobManager.setRecipientFinderForTesting(z -> Collections.emptyList());

        boolean changed = MobManager.setState(bus, 0x42, 7,
                MobState.IDLE, 0, 0f, MobDataDecoder.NO_TARGET);
        assertTrue(changed);
        assertNotNull(MobManager.getSnapshot(0x42));
        assertEquals(MobState.IDLE, MobManager.getSnapshot(0x42).state);
        assertEquals(1, bus.pending(-1));
    }

    @Test
    public void identicalStateWriteIsElided() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        MobManager.setRecipientFinderForTesting(z -> Collections.emptyList());

        MobManager.setState(bus, 0x42, 7, MobState.IDLE, 0, 0f, -1);
        bus.drain(-1);
        boolean changed = MobManager.setState(bus, 0x42, 7,
                MobState.IDLE, 0, 0f, -1);
        assertFalse("identical state must elide write", changed);
        assertEquals(0, bus.pending(-1));
    }

    @Test
    public void stateTransitionPostsNewIntent() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        MobManager.setRecipientFinderForTesting(z -> Collections.emptyList());

        MobManager.setState(bus, 0x42, 7, MobState.IDLE, 0, 0f, -1);
        bus.drain(-1);
        boolean changed = MobManager.setState(bus, 0x42, 7,
                MobState.COMBAT, 0x40, 100f, 0x999);
        assertTrue(changed);
        assertEquals(MobState.COMBAT, MobManager.getSnapshot(0x42).state);
        assertEquals(1, bus.pending(-1));
    }

    @Test
    public void clearForgetsState() {
        MobManager.setState(null, 0x42, 7, MobState.IDLE, 0, 0f, -1);
        assertEquals(1, MobManager.trackedCount());
        MobManager.Snapshot prev = MobManager.clear(0x42);
        assertNotNull(prev);
        assertNull(MobManager.getSnapshot(0x42));
        assertEquals(0, MobManager.trackedCount());
    }

    @Test
    public void resendCurrentForcesBroadcast() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.setState(bus, 0x42, 7, MobState.IDLE, 0, 0f, -1);
        bus.drain(-1);
        MobManager.resendCurrent(bus, 0x42, 7);
        // resend must enqueue a new intent regardless of state diff.
        assertEquals(1, bus.pending(-1));
    }

    @Test
    public void resendNoOpForUnknownMob() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.resendCurrent(bus, 0x999, 7);
        assertEquals(0, bus.pending(-1));
    }

    @Test
    public void nullStateRejected() {
        WorldMessageBus bus = new WorldMessageBus();
        boolean changed = MobManager.setState(bus, 1, 7, null, 0, 0f, -1);
        assertFalse(changed);
        assertNull(MobManager.getSnapshot(1));
    }

    // ─── Fan-out tests ──────────────────────────────────────────────

    @Test
    public void dispatchSendsToEveryRecipient() {
        Player a = makePlayer(1, "A");
        Player b = makePlayer(2, "B");
        Player c = makePlayer(3, "C");
        MobManager.setRecipientFinderForTesting(z -> Arrays.asList(a, b, c));

        MobStateChangeIntent intent = new MobStateChangeIntent(
                0x42, 7, MobState.COMBAT, 0x40, 100f, MobDataDecoder.NO_TARGET);
        MobManager.dispatchStateChange(intent);
        assertEquals(3, captured.size());
        // All recipients see the same intent.
        for (CapturedSend c1 : captured) {
            assertSame(intent, c1.intent);
        }
    }

    @Test
    public void sinkExceptionDoesNotBreakRemainingRecipients() {
        Player a = makePlayer(1, "A");
        Player b = makePlayer(2, "B");
        MobManager.setRecipientFinderForTesting(z -> Arrays.asList(a, b));
        List<Player> reached = new ArrayList<>();
        MobManager.setPacketSinkForTesting((r, i) -> {
            if (r == a) throw new RuntimeException("simulated");
            reached.add(r);
        });

        MobManager.dispatchStateChange(new MobStateChangeIntent(
                1, 7, MobState.IDLE, 0, 0f, -1));
        assertEquals(1, reached.size());
        assertEquals(b, reached.get(0));
    }

    @Test
    public void busHandlerWiringDispatchesIntent() {
        Player witness = makePlayer(0x200, "Witness");
        MobManager.setRecipientFinderForTesting(z -> Collections.singletonList(witness));

        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        MobManager.setState(bus, 0x42, 7, MobState.COMBAT, 0x40, 50f, 0x999);
        bus.drain(-1);

        assertEquals(1, captured.size());
        assertEquals(witness, captured.get(0).recipient);
        assertEquals(0x42, captured.get(0).intent.npcId);
        assertEquals(MobState.COMBAT, captured.get(0).intent.state);
    }

    @Test
    public void defaultSinkBuildsValidMobStateBroadcast() {
        // Use a real player+UDP fixture so MobStateBroadcast can pull
        // a session counter, then verify the packet inner body
        // round-trips through the decoder with intent fields intact.
        Player recipient = server.gameserver.packets.server_udp.PacketTestFixture.newPlayer();
        MobStateChangeIntent intent = new MobStateChangeIntent(
                0x123, 7, MobState.TRANSITION, 0x12, 42.5f, MobDataDecoder.NO_TARGET);

        MobStateBroadcast pkt = new MobStateBroadcast(recipient,
                intent.npcId, intent.state, intent.flagsByte,
                intent.altitude, intent.targetId,
                MobStateBroadcast.ZERO_TAIL);
        java.net.DatagramPacket dp = pkt.getDatagramPackets()[0];
        byte[] data = dp.getData();
        int len = dp.getLength();
        // Wrapper: 0x13 + 2B counter + 2B counter+key + 2B size +
        //          0x03 + 2B seq + 0x2d  = 11 bytes
        byte[] inner = new byte[len - 11];
        System.arraycopy(data, 11, inner, 0, inner.length);

        MobDataDecoder.DecodedMob m = MobDataDecoder.decodeLong(inner);
        assertNotNull(m);
        assertEquals(0x123, m.npcId);
        assertEquals(MobState.TRANSITION, m.state);
        assertEquals(42.5f, m.altitude, 0.001f);
    }
}
