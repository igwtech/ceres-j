package server.database.accounts;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.LinkedList;

import org.junit.After;
import org.junit.Test;

import server.tools.Config;

/**
 * Task #184: accounts listed in the {@code AdminAccounts} config key
 * are force-promoted to {@link Account#GM_ADMIN} in memory on every
 * boot ({@link AccountManager#applyConfigAdmins()}), so the autosave
 * upsert persists {@code gm_level=3} instead of clobbering a live
 * change with the stale in-memory value. Never a downgrade; unlisted
 * accounts are untouched.
 */
public class AccountManagerAdminBootstrapTest {

    @After
    public void tearDown() {
        Config.setAdminAccountsForTesting(null);
        AccountManager.setAccountsForTesting(new LinkedList<>());
    }

    private static Account acct(int id, String name, int gm) {
        Account a = new Account(id);
        a.setUsername(name);
        a.setGmLevel(gm);
        return a;
    }

    @Test
    public void listedAccountPromotedToAdmin() {
        Account a = acct(1, "msn3wolf", Account.GM_PLAYER);
        LinkedList<Account> l = new LinkedList<>();
        l.add(a);
        AccountManager.setAccountsForTesting(l);
        Config.setAdminAccountsForTesting(Arrays.asList("msn3wolf"));

        AccountManager.applyConfigAdmins();

        assertEquals(Account.GM_ADMIN, a.getGmLevel());
    }

    @Test
    public void matchIsCaseInsensitive() {
        Account a = acct(1, "MsN3WoLf", Account.GM_PLAYER);
        LinkedList<Account> l = new LinkedList<>();
        l.add(a);
        AccountManager.setAccountsForTesting(l);
        Config.setAdminAccountsForTesting(Arrays.asList("msn3wolf"));

        AccountManager.applyConfigAdmins();

        assertEquals(Account.GM_ADMIN, a.getGmLevel());
    }

    @Test
    public void unlistedAccountUntouched() {
        Account a = acct(1, "regularjoe", Account.GM_PLAYER);
        LinkedList<Account> l = new LinkedList<>();
        l.add(a);
        AccountManager.setAccountsForTesting(l);
        Config.setAdminAccountsForTesting(Arrays.asList("msn3wolf"));

        AccountManager.applyConfigAdmins();

        assertEquals(Account.GM_PLAYER, a.getGmLevel());
    }

    @Test
    public void neverDowngradesAHigherLevel() {
        // Already GM_ADMIN (3); a future level above GM_ADMIN must not
        // be reduced. Use GM_ADMIN here as the boundary case.
        Account a = acct(1, "msn3wolf", Account.GM_ADMIN);
        LinkedList<Account> l = new LinkedList<>();
        l.add(a);
        AccountManager.setAccountsForTesting(l);
        Config.setAdminAccountsForTesting(Arrays.asList("msn3wolf"));

        AccountManager.applyConfigAdmins();

        assertEquals("must stay GM_ADMIN, not be re-set lower",
                Account.GM_ADMIN, a.getGmLevel());
    }

    @Test
    public void emptyConfigPromotesNobody() {
        Account a = acct(1, "msn3wolf", Account.GM_PLAYER);
        LinkedList<Account> l = new LinkedList<>();
        l.add(a);
        AccountManager.setAccountsForTesting(l);
        Config.setAdminAccountsForTesting(null);

        AccountManager.applyConfigAdmins();

        assertEquals(Account.GM_PLAYER, a.getGmLevel());
    }

    @Test
    public void persistsAcrossASimulatedAutosaveCycle() {
        // The clobber bug: a live gm_level change is overwritten by
        // the autosave upsert, which writes the in-memory value. With
        // the bootstrap, the in-memory value IS GM_ADMIN after load,
        // so the upsert persists 3. Model that here: load → bootstrap
        // → (autosave would read getGmLevel()).
        Account a = acct(7, "msn3wolf", Account.GM_PLAYER); // DB clobbered to 0
        LinkedList<Account> l = new LinkedList<>();
        l.add(a);
        AccountManager.setAccountsForTesting(l);
        Config.setAdminAccountsForTesting(Arrays.asList("msn3wolf"));

        AccountManager.applyConfigAdmins();            // boot
        int whatAutosaveWouldPersist = a.getGmLevel(); // upsert source

        assertEquals("autosave must now persist GM_ADMIN, not 0",
                Account.GM_ADMIN, whatAutosaveWouldPersist);
    }
}
