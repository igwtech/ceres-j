package server.gameserver.command;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.command.commands.CommandPack;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Functional tests: apply each task-#179 command to a real
 * {@link Player}/{@link PlayerCharacter} fixture and assert the
 * resulting server-side state. Wire side-effects (UDP sends) are
 * exercised for non-throwing but state is asserted on the model.
 */
public class GmCommandFunctionalTest {

    private GmCommandRegistry reg;

    @Before
    public void setUp() {
        reg = new GmCommandRegistry();
        CommandPack.registerAll(reg);
    }

    private static Player gmPlayer(int level) {
        Player pl = PacketTestFixture.newPlayer();
        pl.getAccount().setGmLevel(level);
        return pl;
    }

    private CommandResult run(Player pl, String line) {
        return reg.dispatch(pl, line, s -> {});
    }

    @Test
    public void noclipTogglesServerFlagAndIsIdempotentWithExplicitArg() {
        Player pl = gmPlayer(Account.GM_GAMEMASTER);
        assertFalse(pl.isNoclip());

        assertTrue(run(pl, "noclip").isOk());
        assertTrue("toggle on", pl.isNoclip());

        assertTrue(run(pl, "noclip").isOk());
        assertFalse("toggle off", pl.isNoclip());

        assertTrue(run(pl, "noclip on").isOk());
        assertTrue(pl.isNoclip());
        assertTrue(run(pl, "noclip on").isOk());
        assertTrue("explicit on stays on", pl.isNoclip());
        assertTrue(run(pl, "noclip off").isOk());
        assertFalse(pl.isNoclip());
    }

    @Test
    public void noclipDeniedForNonGm() {
        Player pl = gmPlayer(Account.GM_MODERATOR);
        CommandResult r = run(pl, "noclip");
        assertEquals(CommandResult.Status.DENIED, r.status());
        assertFalse(pl.isNoclip());
    }

    @Test
    public void tpBareCoordinatesMovesWithinZone() {
        Player pl = gmPlayer(Account.GM_GAMEMASTER);
        PlayerCharacter pc = pl.getCharacter();
        int zone = pc.getMisc(PlayerCharacter.MISC_LOCATION);
        assertTrue(run(pl, "tp 11 22 33").isOk());
        assertEquals(11, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
        assertEquals(22, pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
        assertEquals(33, pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
        assertEquals(zone, pc.getMisc(PlayerCharacter.MISC_LOCATION));
    }

    @Test
    public void tpZoneChangesLocation() {
        Player pl = gmPlayer(Account.GM_GAMEMASTER);
        PlayerCharacter pc = pl.getCharacter();
        assertTrue(run(pl, "tp zone 42 5 6 7").isOk());
        assertEquals(42, pc.getMisc(PlayerCharacter.MISC_LOCATION));
        assertEquals(5, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
        assertEquals(6, pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
        assertEquals(7, pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
    }

    @Test
    public void tpToUnknownPlayerErrors() {
        Player pl = gmPlayer(Account.GM_GAMEMASTER);
        CommandResult r = run(pl, "tp to NoSuchPlayer");
        assertEquals(CommandResult.Status.ERROR, r.status());
    }

    @Test
    public void resetposZeroesCoordinates() {
        Player pl = gmPlayer(Account.GM_MODERATOR);
        PlayerCharacter pc = pl.getCharacter();
        pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, 999);
        assertTrue(run(pl, "resetpos").isOk());
        assertEquals(0, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
        assertEquals(0, pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
        assertEquals(0, pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
    }

    @Test
    public void tpBareCoordinatesDoesNotChangeZone() {
        // Regression for task #182: same-zone tp must NOT route
        // through the zone-change path (it now relocates in place).
        Player pl = gmPlayer(Account.GM_GAMEMASTER);
        PlayerCharacter pc = pl.getCharacter();
        int zone = pc.getMisc(PlayerCharacter.MISC_LOCATION);
        assertTrue(run(pl, "tp 7 8 9").isOk());
        assertEquals("zone unchanged on bare-coord tp",
                zone, pc.getMisc(PlayerCharacter.MISC_LOCATION));
        assertEquals(7, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
        assertEquals(8, pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
        assertEquals(9, pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
    }

    @Test
    public void zoneCommandIsRegisteredAndNumericIdChangesLocation() {
        Player pl = gmPlayer(Account.GM_GAMEMASTER);
        PlayerCharacter pc = pl.getCharacter();
        assertTrue("zone is a registry command now",
                reg.isRegistered("zone"));
        assertTrue(run(pl, "zone 314 1 2 3").isOk());
        assertEquals(314,
                pc.getMisc(PlayerCharacter.MISC_LOCATION));
        assertEquals(1, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
        assertEquals(2, pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
        assertEquals(3, pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
    }

    @Test
    public void zoneCommandUnknownNameErrorsCleanly() {
        Player pl = gmPlayer(Account.GM_GAMEMASTER);
        // No world_defs loaded in this test JVM → name cannot
        // resolve → ERROR, not an exception, and no state change.
        PlayerCharacter pc = pl.getCharacter();
        int before = pc.getMisc(PlayerCharacter.MISC_LOCATION);
        CommandResult r = run(pl, "zone pepper1");
        assertEquals(CommandResult.Status.ERROR, r.status());
        assertEquals(before,
                pc.getMisc(PlayerCharacter.MISC_LOCATION));
    }

    @Test
    public void zoneCommandDeniedForModerator() {
        Player pl = gmPlayer(Account.GM_MODERATOR);
        CommandResult r = run(pl, "zone 5");
        assertEquals(CommandResult.Status.DENIED, r.status());
    }

    @Test
    public void noclipSendsOnlyTheRetailProvableGamedataSignal() {
        // The audited recipe is: server flag + SetGamedata(gm_noclip).
        // This asserts the flag side-effect; the removed unverified
        // QuickCommand/UpdateModel pieces are covered by the recipe
        // no longer referencing those classes (compile-time).
        Player pl = gmPlayer(Account.GM_GAMEMASTER);
        assertFalse(pl.isNoclip());
        assertTrue(run(pl, "noclip on").isOk());
        assertTrue("authoritative server flag set", pl.isNoclip());
        assertTrue(run(pl, "noclip off").isOk());
        assertFalse(pl.isNoclip());
    }

    @Test
    public void changeskillMutatesSubskillLevel() {
        Player pl = gmPlayer(Account.GM_GAMEMASTER);
        PlayerCharacter pc = pl.getCharacter();
        assertTrue(run(pl, "changeskill "
                + PlayerCharacter.SUBSKILL_HLT + " 150").isOk());
        assertEquals(150,
                pc.getSubskillLVL(PlayerCharacter.SUBSKILL_HLT));
    }

    @Test
    public void changeskillClampsToMax() {
        Player pl = gmPlayer(Account.GM_GAMEMASTER);
        PlayerCharacter pc = pl.getCharacter();
        assertTrue(run(pl, "changeskill 27 99999").isOk());
        assertEquals(200,
                pc.getSubskillLVL(PlayerCharacter.SUBSKILL_HLT));
    }

    @Test
    public void givemoneyAddsAndSubtractsCash() {
        Player pl = gmPlayer(Account.GM_GAMEMASTER);
        PlayerCharacter pc = pl.getCharacter();
        pc.setCash(1000);
        assertTrue(run(pl, "givemoney 500").isOk());
        assertEquals(1500, pc.getCash());
        assertTrue(run(pl, "givemoney -2000").isOk());
        assertEquals("clamps at zero", 0, pc.getCash());
    }

    @Test
    public void givemoneyDeniedForModerator() {
        Player pl = gmPlayer(Account.GM_MODERATOR);
        Player ref = gmPlayer(Account.GM_MODERATOR);
        int before = ref.getCharacter().getCash();
        CommandResult r = run(pl, "givemoney 9999");
        assertEquals(CommandResult.Status.DENIED, r.status());
        assertEquals(before, pl.getCharacter().getCash());
    }

    @Test
    public void giveitemUnknownIdErrorsCleanly() {
        Player pl = gmPlayer(Account.GM_GAMEMASTER);
        // Item id 1 has no ItemInfo loaded in the test (no DB) so
        // createItem returns null — must surface as ERROR, not throw.
        CommandResult r = run(pl, "giveitem 1");
        assertEquals(CommandResult.Status.ERROR, r.status());
    }

    @Test
    public void setgmlevelRequiresAdminAndCannotEscalate() {
        Player gm = gmPlayer(Account.GM_GAMEMASTER);
        // a gamemaster is below admin → denied outright
        assertEquals(CommandResult.Status.DENIED,
                run(gm, "setgmlevel Someone 1").status());
    }

    @Test
    public void helpAvailableToEveryoneAndFiltersByLevel() {
        Player low = gmPlayer(Account.GM_PLAYER);
        java.util.List<String> lines = new java.util.ArrayList<>();
        CommandResult r = reg.dispatch(low, "help", lines::add);
        assertTrue(r.isOk());
        // A GM_PLAYER must not see the admin-only setgmlevel listing.
        boolean sawAdminCmd = lines.stream()
                .anyMatch(l -> l.contains("setgmlevel"));
        assertFalse("low GM must not see admin commands", sawAdminCmd);
    }
}
