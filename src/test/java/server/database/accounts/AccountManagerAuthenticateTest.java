package server.database.accounts;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.LinkedList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.tools.Config;

/**
 * Tests for {@link AccountManager#authenticate} — the richer
 * auth path that distinguishes user-not-found, bad-password,
 * banned, and OK so {@code AuthB} can log the right diagnostic.
 *
 * <p>Reaches into the static {@code accountList} via reflection
 * to install a known account fixture without touching the
 * database. Restores the prior state in tearDown.
 */
public class AccountManagerAuthenticateTest {

    @SuppressWarnings("unchecked")
    private static LinkedList<Account> accountList() {
        try {
            Field f = AccountManager.class.getDeclaredField("accountList");
            f.setAccessible(true);
            LinkedList<Account> list = (LinkedList<Account>) f.get(null);
            if (list == null) {
                // AccountManager.init() hasn't run — install an empty
                // list so tests can populate it directly.
                list = new LinkedList<>();
                f.set(null, list);
            }
            return list;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private LinkedList<Account> snapshot;
    private String prevAutoCreate;

    @Before
    public void setUp() {
        snapshot = new LinkedList<>(accountList());
        accountList().clear();
        // Save / clear AutoCreateAccounts so each test owns it.
        prevAutoCreate = Config.getProperty("AutoCreateAccounts");
        Config.setProperty("AutoCreateAccounts", "false");

        Account good = new Account(1);
        good.setUsername("alice");
        good.setPassword("password123");
        accountList().add(good);

        Account banned = new Account(2);
        banned.setUsername("blocked");
        banned.setPassword("password123");
        banned.setStatus("banned");
        accountList().add(banned);
    }

    @After
    public void tearDown() {
        accountList().clear();
        accountList().addAll(snapshot);
        Config.setProperty("AutoCreateAccounts",
                prevAutoCreate == null ? "false" : prevAutoCreate);
    }

    // ─── OK ────────────────────────────────────────────────────────

    @Test
    public void okOnCorrectCredentials() {
        AccountManager.AuthResult r = AccountManager.authenticate(
                "alice", "password123");
        assertEquals(AccountManager.AuthOutcome.OK, r.outcome);
        assertNotNull(r.account);
        assertEquals("alice", r.account.getUsername());
    }

    @Test
    public void usernameMatchIsCaseInsensitive() {
        AccountManager.AuthResult r = AccountManager.authenticate(
                "ALICE", "password123");
        assertEquals(AccountManager.AuthOutcome.OK, r.outcome);
    }

    // ─── NOT_FOUND ────────────────────────────────────────────────

    @Test
    public void notFoundOnUnknownUserWhenAutoCreateOff() {
        Config.setProperty("AutoCreateAccounts", "false");
        AccountManager.AuthResult r = AccountManager.authenticate(
                "nobody", "anything");
        assertEquals(AccountManager.AuthOutcome.NOT_FOUND, r.outcome);
        assertNull(r.account);
    }

    @Test
    public void unknownUserIsAutoCreatedWhenAutoCreateOn() {
        Config.setProperty("AutoCreateAccounts", "true");
        // Tests run without a DB connection so saveAccount won't
        // actually persist — but the in-memory create+add path
        // should still produce an OK result.
        AccountManager.AuthResult r = AccountManager.authenticate(
                "newuser", "freshpass");
        assertEquals(AccountManager.AuthOutcome.OK, r.outcome);
        assertNotNull(r.account);
        assertEquals("newuser", r.account.getUsername());
    }

    // ─── BAD_PASSWORD ─────────────────────────────────────────────

    @Test
    public void badPasswordOnExistingUser() {
        AccountManager.AuthResult r = AccountManager.authenticate(
                "alice", "wrong");
        assertEquals(AccountManager.AuthOutcome.BAD_PASSWORD, r.outcome);
        assertNull("account must NOT be returned on bad password",
                r.account);
    }

    @Test
    public void badPasswordEvenWithAutoCreateOn() {
        // With AutoCreateAccounts=true, an UNKNOWN user gets created.
        // But an EXISTING user with a wrong password must still fail
        // — auto-create is only for new accounts.
        Config.setProperty("AutoCreateAccounts", "true");
        AccountManager.AuthResult r = AccountManager.authenticate(
                "alice", "wrong");
        assertEquals(AccountManager.AuthOutcome.BAD_PASSWORD, r.outcome);
    }

    // ─── BANNED ───────────────────────────────────────────────────

    @Test
    public void bannedAccountReportsBanned() {
        AccountManager.AuthResult r = AccountManager.authenticate(
                "blocked", "password123");
        assertEquals(AccountManager.AuthOutcome.BANNED, r.outcome);
        assertNull(r.account);
    }

    @Test
    public void bannedAccountStillRequiresCorrectPassword() {
        // Ordering: bad-password check runs before ban check.
        // That's intentional — don't leak ban status to credential
        // probers. Verify the order.
        AccountManager.AuthResult r = AccountManager.authenticate(
                "blocked", "wrong");
        assertEquals(AccountManager.AuthOutcome.BAD_PASSWORD, r.outcome);
    }

    // ─── Backward compat ──────────────────────────────────────────

    @Test
    public void getAccountWrapperReturnsNullOnAnyFailure() {
        assertNull(AccountManager.getAccount("alice", "wrong"));
        assertNull(AccountManager.getAccount("nobody", "x"));
        assertNull(AccountManager.getAccount("blocked", "password123"));
    }

    @Test
    public void getAccountWrapperReturnsAccountOnSuccess() {
        Account a = AccountManager.getAccount("alice", "password123");
        assertNotNull(a);
        assertEquals("alice", a.getUsername());
    }

    @Test
    public void nullUsernameDoesNotThrow() {
        AccountManager.AuthResult r = AccountManager.authenticate(
                null, "anything");
        assertEquals(AccountManager.AuthOutcome.NOT_FOUND, r.outcome);
    }
}
