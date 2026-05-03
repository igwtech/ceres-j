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
 * Functional tests for {@link DroneManager}.
 *
 * <p>Each test uses the test-seam strategies on {@link DroneManager}
 * to inject a fake pilot lookup, recipient list, and packet sink so
 * we can verify dispatch logic without booting the real
 * {@code Zone}/{@code PlayerManager} stack or opening UDP sockets.
 *
 * <p>Bus integration tests post a {@link DroneControlIntent} through
 * a {@link WorldMessageBus} that has the manager's handler
 * installed, then drain the bus and verify the captured packets.
 */
public class DroneManagerTest {

    /** Captured (recipient, intent) tuple for assertions. */
    static final class CapturedSend {
        final Player recipient;
        final DroneControlIntent intent;
        CapturedSend(Player r, DroneControlIntent i) {
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
        DroneManager.setPacketSinkForTesting(
                (r, i) -> captured.add(new CapturedSend(r, i)));
    }

    @After
    public void tearDown() {
        DroneManager.resetForTesting();
    }

    @Test
    public void echoFansOutToEveryRecipientExceptPilot() {
        Player pilot   = makePlayer(0x100, "Pilot");
        Player witness = makePlayer(0x200, "Witness");
        Player friend  = makePlayer(0x300, "Friend");

        DroneManager.setPilotLookupForTesting(uid -> uid == 0x100 ? pilot : null);
        DroneManager.setRecipientFinderForTesting(p ->
                Arrays.asList(pilot, witness, friend));

        DroneControlIntent intent = new DroneControlIntent(
                0x100, 0x42, 1.0f, 2.0f, 3.0f, new byte[20]);
        DroneManager.dispatchDroneIntent(intent);

        assertEquals("pilot must not receive own echo",
                2, captured.size());
        assertTrue(captured.stream().anyMatch(c -> c.recipient == witness));
        assertTrue(captured.stream().anyMatch(c -> c.recipient == friend));
        assertTrue(captured.stream().noneMatch(c -> c.recipient == pilot));
    }

    @Test
    public void unknownPilotDropsCleanly() {
        DroneManager.setPilotLookupForTesting(uid -> null);
        DroneManager.setRecipientFinderForTesting(p ->
                Collections.emptyList());

        DroneControlIntent intent = new DroneControlIntent(
                0x999, 1, 0f, 0f, 0f, new byte[20]);
        DroneManager.dispatchDroneIntent(intent);

        assertTrue(captured.isEmpty());
    }

    @Test
    public void emptyRecipientListIsNoOp() {
        Player pilot = makePlayer(0x100, "Solo");
        DroneManager.setPilotLookupForTesting(uid -> pilot);
        DroneManager.setRecipientFinderForTesting(p ->
                Collections.singletonList(pilot)); // only the pilot

        DroneControlIntent intent = new DroneControlIntent(
                0x100, 7, 0f, 0f, 0f, new byte[20]);
        DroneManager.dispatchDroneIntent(intent);

        assertTrue(captured.isEmpty());
    }

    @Test
    public void sinkExceptionsAreSwallowed() {
        Player pilot   = makePlayer(0x100, "Pilot");
        Player r1      = makePlayer(0x200, "R1");
        Player r2      = makePlayer(0x300, "R2");
        DroneManager.setPilotLookupForTesting(uid -> pilot);
        DroneManager.setRecipientFinderForTesting(p ->
                Arrays.asList(r1, r2));
        // Sink throws on r1; r2 still gets sent to.
        List<Player> reached = new ArrayList<>();
        DroneManager.setPacketSinkForTesting((rec, i) -> {
            if (rec == r1) throw new RuntimeException("simulated boom");
            reached.add(rec);
        });

        DroneManager.dispatchDroneIntent(new DroneControlIntent(
                0x100, 1, 0f, 0f, 0f, new byte[20]));

        assertEquals(1, reached.size());
        assertEquals(r2, reached.get(0));
    }

    @Test
    public void busHandlerWiringDispatchesIntent() {
        Player pilot   = makePlayer(0x100, "Pilot");
        Player witness = makePlayer(0x200, "Witness");
        DroneManager.setPilotLookupForTesting(uid -> pilot);
        DroneManager.setRecipientFinderForTesting(p ->
                Arrays.asList(pilot, witness));

        WorldMessageBus bus = new WorldMessageBus();
        DroneManager.installBusHandlers(bus);
        bus.post(new DroneControlIntent(0x100, 0x42,
                10f, 20f, 30f, new byte[20]));
        bus.drain(-1);

        assertEquals(1, captured.size());
        assertEquals(witness, captured.get(0).recipient);
        assertEquals(0x42, captured.get(0).intent.droneId);
        assertEquals(20f, captured.get(0).intent.posY, 0.0001f);
    }

    @Test
    public void buildEchoEncodesIntentFieldsIntoPacketStructure() {
        // Use a real player with a UDP-fixture so MobStateBroadcast can
        // pull a session counter. Verify the packet's inner body
        // round-trips through the decoder with the right droneId,
        // state, and altitude.
        Player recipient = server.gameserver.packets.server_udp.PacketTestFixture.newPlayer();
        DroneControlIntent intent = new DroneControlIntent(
                0x12345, 0x99, 100f, 200f, 9876.5f, new byte[20]);

        MobStateBroadcast pkt = DroneManager.buildEchoFor(recipient, intent);
        java.net.DatagramPacket dp = pkt.getDatagramPackets()[0];

        // Strip wrapper: 0x13 + 2B counter + 2B counter+key + 2B size
        //              + 0x03 + 2B seq + 0x2d  = 11 bytes
        byte[] data = dp.getData();
        int len = dp.getLength();
        byte[] inner = new byte[len - 11];
        System.arraycopy(data, 11, inner, 0, inner.length);
        assertEquals(MobDataDecoder.LONG_LEN, inner.length);

        MobDataDecoder.DecodedMob m = MobDataDecoder.decodeLong(inner);
        assertNotNull(m);
        assertEquals(0x99, m.npcId);
        assertEquals(MobState.COMBAT, m.state);
        assertEquals(9876.5f, m.altitude, 0.001f);
        assertEquals(MobDataDecoder.NO_TARGET, m.targetId);
    }
}
