package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.WorldMessageBus;
import server.gameserver.npc.DroneControlIntent;

/**
 * Unit + functional tests for {@link DroneControlPacket}.
 *
 * <p>End-to-end: raw subPacket bytes (with the {@code 03 [seq2] 2d}
 * wrapper) → execute → bus → DroneControlIntent.
 */
public class DroneControlPacketTest {

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s+", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static Player makePlayer(int uid) {
        Account acc = new Account(uid);
        acc.setUsername("u" + uid);
        Player pl = new Player(acc);
        PlayerCharacter pc = new PlayerCharacter();
        pc.setMisc(PlayerCharacter.MISC_ID, uid);
        pc.setName("Pilot");
        try {
            Field f = Player.class.getDeclaredField("pc");
            f.setAccessible(true);
            f.set(pl, pc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return pl;
    }

    @Test
    public void postsIntentForRetailControlFrame() {
        WorldMessageBus bus = new WorldMessageBus();
        java.util.List<DroneControlIntent> seen = new java.util.ArrayList<>();
        bus.registerHandler(DroneControlIntent.class,
                i -> seen.add((DroneControlIntent) i));

        // Build the full subPacket: 03 [seq2] 2d + 37-byte inner.
        byte[] sub = hex(
            "03 aa bb 2d " +
            "d6030000 02 ce2bbb43 a452dbc3 188a4545" +
            "00000000 00000000 00000000 00000000 00000000");
        new DroneControlPacket(sub, bus).execute(makePlayer(0x18678));
        bus.drain(-1);

        assertEquals(1, seen.size());
        DroneControlIntent i = seen.get(0);
        assertEquals(0x18678, i.pilotUid);
        assertEquals(0x000003d6, i.droneId);
        assertEquals(374.34f, i.posX, 0.5f);
        assertEquals(-438.65f, i.posY, 0.5f);
        assertEquals(3160.63f, i.posZ, 1.0f);
        assertEquals(20, i.tail.length);
    }

    @Test
    public void heartbeatLoggedButNotPostedAsControl() {
        WorldMessageBus bus = new WorldMessageBus();
        java.util.List<DroneControlIntent> seen = new java.util.ArrayList<>();
        bus.registerHandler(DroneControlIntent.class,
                i -> seen.add((DroneControlIntent) i));

        // 5-byte heartbeat: 03 [seq2] 2d + 5B
        byte[] sub = hex("03 00 00 2d 00 00 00 00 0b");
        new DroneControlPacket(sub, bus).execute(makePlayer(1));
        bus.drain(-1);

        // Heartbeat does not currently translate to a
        // DroneControlIntent — the subsystem only cares about
        // position frames. Phase 3's DroneManager will register a
        // separate heartbeat handler.
        assertTrue(seen.isEmpty());
    }

    @Test
    public void shortPacketDropsCleanly() {
        WorldMessageBus bus = new WorldMessageBus();
        // Smaller than INNER_OFFSET=4 → should drop without throwing.
        new DroneControlPacket(new byte[]{0x03, 0x00, 0x00}, bus)
                .execute(makePlayer(1));
        assertEquals(0, bus.pending(-1));
    }

    @Test
    public void wrongClassByteDropsCleanly() {
        WorldMessageBus bus = new WorldMessageBus();
        java.util.List<DroneControlIntent> seen = new java.util.ArrayList<>();
        bus.registerHandler(DroneControlIntent.class,
                i -> seen.add((DroneControlIntent) i));

        // Class byte = 0x03 instead of 0x02 — decoder returns null,
        // packet dropped.
        byte[] sub = hex(
            "03 00 00 2d " +
            "01000000 03 00000000 00000000 00000000" +
            "00000000 00000000 00000000 00000000 00000000");
        new DroneControlPacket(sub, bus).execute(makePlayer(1));
        bus.drain(-1);

        assertTrue(seen.isEmpty());
    }
}
