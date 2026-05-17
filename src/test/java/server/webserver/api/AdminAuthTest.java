package server.webserver.api;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.LinkedList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.accounts.Account;
import server.database.accounts.AccountManager;
import server.tools.Config;

/**
 * Unit tests for {@link AdminAuth} — the security gate the
 * collaborator's prototype completely lacked.
 *
 * <p>Covers: API disabled when no secret configured, placeholder
 * token treated as unset, valid/invalid token, and admin-account
 * credential fallback (including banned admin rejection).
 */
public class AdminAuthTest {

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

    private LinkedList<Account> snapshot;
    private String prevToken;

    @Before
    public void setUp() {
        snapshot = new LinkedList<>(accountList());
        accountList().clear();
        prevToken = Config.getProperty("WebAdminToken");
    }

    @After
    public void tearDown() {
        accountList().clear();
        accountList().addAll(snapshot);
        Config.setProperty("WebAdminToken", prevToken);
    }

    @Test
    public void apiDisabledWhenNoTokenAndNoAdminAccount() {
        Config.setProperty("WebAdminToken", "");
        assertEquals(AdminAuth.Decision.DISABLED,
                AdminAuth.authorize("anything", null, null));
    }

    @Test
    public void placeholderTokenCountsAsUnset() {
        Config.setProperty("WebAdminToken", AdminAuth.PLACEHOLDER);
        assertEquals(AdminAuth.Decision.DISABLED,
                AdminAuth.authorize(AdminAuth.PLACEHOLDER, null, null));
    }

    @Test
    public void validTokenAuthorizes() {
        Config.setProperty("WebAdminToken", "s3cr3t-token");
        assertEquals(AdminAuth.Decision.OK,
                AdminAuth.authorize("s3cr3t-token", null, null));
    }

    @Test
    public void wrongTokenRejected() {
        Config.setProperty("WebAdminToken", "s3cr3t-token");
        assertEquals(AdminAuth.Decision.UNAUTHORIZED,
                AdminAuth.authorize("nope", null, null));
    }

    @Test
    public void missingTokenRejectedWhenApiEnabled() {
        Config.setProperty("WebAdminToken", "s3cr3t-token");
        assertEquals(AdminAuth.Decision.UNAUTHORIZED,
                AdminAuth.authorize(null, null, null));
    }

    @Test
    public void adminAccountCredentialAuthorizes() {
        Config.setProperty("WebAdminToken", "");
        Account admin = new Account(1);
        admin.setUsername("gm");
        admin.setPassword("pw");
        admin.setStatusCode(Account.STATUS_ADMIN);
        accountList().add(admin);

        assertEquals(AdminAuth.Decision.OK,
                AdminAuth.authorize(null, "gm", "pw"));
    }

    @Test
    public void nonAdminAccountRejected() {
        Config.setProperty("WebAdminToken", "");
        Account normal = new Account(2);
        normal.setUsername("joe");
        normal.setPassword("pw");
        accountList().add(normal);

        // Account exists but is not admin → API has no admin enabled.
        assertEquals(AdminAuth.Decision.DISABLED,
                AdminAuth.authorize(null, "joe", "pw"));
    }

    @Test
    public void bannedAdminRejected() {
        Config.setProperty("WebAdminToken", "tok");
        Account admin = new Account(3);
        admin.setUsername("gm");
        admin.setPassword("pw");
        admin.setStatusCode(Account.STATUS_BANNED);
        accountList().add(admin);

        assertEquals(AdminAuth.Decision.UNAUTHORIZED,
                AdminAuth.authorize(null, "gm", "pw"));
    }
}
