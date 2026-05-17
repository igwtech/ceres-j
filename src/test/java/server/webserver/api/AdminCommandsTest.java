package server.webserver.api;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.LinkedList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;

import server.database.accounts.Account;
import server.database.accounts.AccountManager;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.PlayerManager;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Unit + functional tests for {@link AdminCommands}.
 *
 * <p>Each test installs a fixture player into {@code PlayerManager}
 * via reflection (the established no-public-seam pattern used by
 * {@code ChatManagerHandlerTest}), invokes a command through
 * {@link AdminCommands#execute}, and asserts both the parsed-param
 * outcome (status code / message) and the resulting state mutation.
 */
public class AdminCommandsTest {

    @SuppressWarnings("unchecked")
    private static LinkedList<Player> playerList() {
        try {
            Field f = PlayerManager.class.getDeclaredField("playerList");
            f.setAccessible(true);
            return (LinkedList<Player>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static LinkedList<Account> accountList() {
        try {
            Field f = AccountManager.class.getDeclaredField("accountList");
            f.setAccessible(true);
            LinkedList<Account> list = (LinkedList<Account>) f.get(null);
            if (list == null) {
                list = new LinkedList<>();
                f.set(null, list);
            }
            return list;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private LinkedList<Player> playerSnapshot;
    private LinkedList<Account> accountSnapshot;
    private Player runner;

    private static Player makePlayer(String name) {
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setName(name);
        return pl;
    }

    @Before
    public void setUp() {
        playerSnapshot = new LinkedList<>(playerList());
        playerList().clear();
        accountSnapshot = new LinkedList<>(accountList());
        accountList().clear();

        runner = makePlayer("Runner");
        runner.setloggedin();
        playerList().add(runner);
    }

    @After
    public void tearDown() {
        playerList().clear();
        playerList().addAll(playerSnapshot);
        accountList().clear();
        accountList().addAll(accountSnapshot);
    }

    private static JsonObject params(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            Object val = kv[i + 1];
            if (val instanceof Number) {
                o.addProperty((String) kv[i], (Number) val);
            } else if (val instanceof Boolean) {
                o.addProperty((String) kv[i], (Boolean) val);
            } else {
                o.addProperty((String) kv[i], String.valueOf(val));
            }
        }
        return o;
    }

    // ─── param validation ──────────────────────────────────────────

    @Test
    public void unknownCommandIsBadRequest() {
        AdminCommands.Result r = AdminCommands.execute("frobnicate",
                new JsonObject());
        assertFalse(r.ok);
        assertEquals(400, r.httpStatus);
    }

    @Test
    public void missingCommandIsBadRequest() {
        AdminCommands.Result r = AdminCommands.execute(null,
                new JsonObject());
        assertFalse(r.ok);
        assertEquals(400, r.httpStatus);
    }

    @Test
    public void missingParamsRejected() {
        AdminCommands.Result r = AdminCommands.execute("set_hp",
                params("player", "Runner")); // no hp
        assertFalse(r.ok);
        assertEquals(400, r.httpStatus);
    }

    @Test
    public void unknownPlayerIsNotFound() {
        AdminCommands.Result r = AdminCommands.execute("set_hp",
                params("player", "Ghost", "hp", 10));
        assertFalse(r.ok);
        assertEquals(404, r.httpStatus);
    }

    // ─── effect tests ──────────────────────────────────────────────

    @Test
    public void setHpMutatesCharacter() {
        runner.getCharacter().setHealth(100);
        AdminCommands.Result r = AdminCommands.execute("set_hp",
                params("player", "Runner", "hp", 777));
        assertTrue(r.message, r.ok);
        assertEquals(200, r.httpStatus);
        assertEquals(777, runner.getCharacter().getHealth());
    }

    @Test
    public void setCashMutatesCharacter() {
        AdminCommands.Result r = AdminCommands.execute("set_cash",
                params("player", "Runner", "amount", 50000));
        assertTrue(r.ok);
        assertEquals(50000, runner.getCharacter().getCash());
    }

    @Test
    public void setRankMutatesCharacter() {
        AdminCommands.execute("set_rank",
                params("player", "Runner", "rank", 42));
        assertEquals(42, runner.getCharacter().getRank());
    }

    @Test
    public void setFactionMutatesCharacter() {
        AdminCommands.execute("set_faction",
                params("player", "Runner", "faction", 9));
        assertEquals(9, runner.getCharacter()
                .getMisc(PlayerCharacter.MISC_FACTION));
    }

    @Test
    public void teleportSetsLocation() {
        AdminCommands.Result r = AdminCommands.execute("teleport",
                params("player", "Runner", "zone", 88));
        assertTrue(r.ok);
        assertEquals(88, runner.getCharacter()
                .getMisc(PlayerCharacter.MISC_LOCATION));
    }

    @Test
    public void teleportRejectsNonPositiveZone() {
        AdminCommands.Result r = AdminCommands.execute("teleport",
                params("player", "Runner", "zone", 0));
        assertFalse(r.ok);
        assertEquals(400, r.httpStatus);
    }

    @Test
    public void setStatMapsStrToSkillSlot() {
        AdminCommands.Result r = AdminCommands.execute("set_stat",
                params("player", "Runner", "stat", "STR", "value", 60));
        assertTrue(r.message, r.ok);
        assertEquals(60, runner.getCharacter()
                .getSkillLVL(PlayerCharacter.STR));
    }

    @Test
    public void setStatRejectsUnknownStat() {
        AdminCommands.Result r = AdminCommands.execute("set_stat",
                params("player", "Runner", "stat", "LUCK", "value", 1));
        assertFalse(r.ok);
        assertEquals(400, r.httpStatus);
    }

    @Test
    public void killZeroesHealth() {
        runner.getCharacter().setHealth(500);
        runner.getCharacter().setMaxHealth(500);
        AdminCommands.Result r = AdminCommands.execute("kill",
                params("player", "Runner"));
        assertTrue(r.ok);
        assertEquals(0, runner.getCharacter().getHealth());
    }

    @Test
    public void resurrectRestoresPools() {
        runner.getCharacter().setMaxHealth(400);
        runner.getCharacter().setMaxPsi(300);
        runner.getCharacter().setMaxStamina(200);
        runner.getCharacter().setHealth(0);
        AdminCommands.Result r = AdminCommands.execute("resurrect",
                params("player", "Runner"));
        assertTrue(r.ok);
        assertEquals(400, runner.getCharacter().getHealth());
        assertEquals(300, runner.getCharacter().getPsi());
        assertEquals(200, runner.getCharacter().getStamina());
    }

    @Test
    public void banSetsAccountStatusAndKicks() {
        Account acc = new Account(99);
        acc.setUsername("victim");
        acc.setPassword("pw");
        accountList().add(acc);

        AdminCommands.Result r = AdminCommands.execute("ban",
                params("account", "victim"));
        assertTrue(r.message, r.ok);
        assertTrue(acc.isBanned());
    }

    @Test
    public void unbanClearsStatus() {
        Account acc = new Account(98);
        acc.setUsername("freed");
        acc.setPassword("pw");
        acc.setStatusCode(Account.STATUS_BANNED);
        accountList().add(acc);

        AdminCommands.execute("unban", params("account", "freed"));
        assertFalse(acc.isBanned());
    }

    @Test
    public void setAdminGrantsAndRevokes() {
        Account acc = new Account(97);
        acc.setUsername("staff");
        acc.setPassword("pw");
        accountList().add(acc);

        AdminCommands.execute("set_admin",
                params("account", "staff", "admin", true));
        assertTrue(acc.isAdmin());

        AdminCommands.execute("set_admin",
                params("account", "staff", "admin", false));
        assertFalse(acc.isAdmin());
    }

    @Test
    public void setAdminUnknownAccountIsNotFound() {
        AdminCommands.Result r = AdminCommands.execute("set_admin",
                params("account", "nobody", "admin", true));
        assertFalse(r.ok);
        assertEquals(404, r.httpStatus);
    }

    @Test
    public void broadcastReportsRecipients() {
        AdminCommands.Result r = AdminCommands.execute("broadcast",
                params("message", "server restart soon"));
        assertTrue(r.ok);
        assertTrue(r.data.containsKey("recipients"));
    }

    @Test
    public void broadcastRequiresMessage() {
        AdminCommands.Result r = AdminCommands.execute("broadcast",
                new JsonObject());
        assertFalse(r.ok);
        assertEquals(400, r.httpStatus);
    }

    @Test
    public void saveDbReturnsOk() {
        AdminCommands.Result r = AdminCommands.execute("save_db",
                new JsonObject());
        assertTrue(r.ok);
        assertEquals(200, r.httpStatus);
    }
}
