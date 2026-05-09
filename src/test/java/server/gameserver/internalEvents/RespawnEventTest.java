package server.gameserver.internalEvents;

import static org.junit.Assert.*;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Functional test for {@link RespawnEvent} — Phase 9
 * placeholder respawn that fires 3 seconds after death,
 * restores pools to max, and ForcedZoning's the player back
 * to the same zone.
 *
 * <p>The full GenRep / inventory-loss / item-drop chain is
 * still pending (Phase 9 acceptance gate); this event ships
 * the minimum that prevents the corpse from staying dead
 * forever.
 */
public class RespawnEventTest {

    @Test
    public void executeRestoresHealthToMax() {
        Player pl = PacketTestFixture.newPlayer();
        PlayerCharacter pc = pl.getCharacter();
        // Simulate fatal damage: HP dropped to 0 by applyDamage.
        pc.setHealth(0);

        new RespawnEvent().execute(pl);

        assertEquals("HP must be restored to maxHealth",
                pc.getMaxHealth(), pc.getHealth());
    }

    @Test
    public void executeRestoresPsiAndStamina() {
        // The fixture player's pools may already be at max
        // depending on PlayerCharacter setup. Drop both, then
        // verify they're restored.
        Player pl = PacketTestFixture.newPlayer();
        PlayerCharacter pc = pl.getCharacter();
        pc.setPsi(0);
        pc.setStamina(0);

        new RespawnEvent().execute(pl);

        assertEquals("PSI restored to max",
                pc.getMaxPsi(), pc.getPsi());
        assertEquals("STA restored to max",
                pc.getMaxStamina(), pc.getStamina());
    }

    @Test
    public void executeIsSafeWithNullCharacter() throws Exception {
        // Defensive: handler must short-circuit if pc is null
        // (e.g., character was deleted between death and
        // respawn).
        Player pl = PacketTestFixture.newPlayer();
        java.lang.reflect.Field f = Player.class
                .getDeclaredField("pc");
        f.setAccessible(true);
        f.set(pl, null);

        new RespawnEvent().execute(pl);
        // Pass = no exception escaped.
    }

    @Test
    public void executeIsSafeWithNullPlayer() {
        // Defensive: player was disconnected between death and
        // respawn. Cast disambiguates Player vs
        // GameServerTCPConnection overloads.
        new RespawnEvent().execute((Player) null);
        // Pass = no NPE.
    }

    @Test
    public void scheduleDelayIsThreeSecondsFromNow() {
        // The constructor sets eventTime = now + 3000ms. The
        // value must be in the near future, not in the past or
        // far future. Test catches a regression that flips the
        // sign or zeroes the delay.
        long before = server.tools.Timer.getRealtime();
        RespawnEvent e = new RespawnEvent();
        long after = server.tools.Timer.getRealtime();

        assertTrue("eventTime must be at least RESPAWN_DELAY_MS "
                + "in the future, got " + e.eventTime
                + " vs now=" + before,
                e.eventTime >= before + RespawnEvent.RESPAWN_DELAY_MS);
        assertTrue("eventTime must be at most "
                + "RESPAWN_DELAY_MS + a tick in the future",
                e.eventTime <= after + RespawnEvent.RESPAWN_DELAY_MS + 100);
    }

    @Test
    public void multipleRespawnsAreIdempotent() {
        // If the same player is respawned twice (e.g. double
        // RespawnEvent scheduled by accident), HP stays at
        // max — no overflow or off-by-one.
        Player pl = PacketTestFixture.newPlayer();
        PlayerCharacter pc = pl.getCharacter();
        pc.setHealth(0);

        new RespawnEvent().execute(pl);
        int afterFirst = pc.getHealth();
        new RespawnEvent().execute(pl);
        int afterSecond = pc.getHealth();

        assertEquals("two respawns leave HP at the same value",
                afterFirst, afterSecond);
        assertEquals("HP at max",
                pc.getMaxHealth(), afterSecond);
    }
}
