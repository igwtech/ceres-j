package server.database.accounts;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

/**
 * Pure-unit tests for the {@link Account#getUuid()} / {@link Account#setUuid(UUID)}
 * accessors added alongside the schema v5 migration. The wire-protocol integer
 * {@link Account#getId()} is unchanged; UUIDs are an additional identifier
 * surfaced for SOAP API integrations.
 */
public class AccountUuidTest {

    @Test
    public void newAccountStartsWithoutUuid() {
        Account ua = new Account(1);
        assertNull("Freshly constructed accounts should have no UUID until "
                + "AccountManager mints one or the column is loaded from DB",
                ua.getUuid());
    }

    @Test
    public void setUuidRoundTripsThroughGetUuid() {
        Account ua = new Account(2);
        UUID expected = UUID.randomUUID();
        ua.setUuid(expected);
        assertEquals(expected, ua.getUuid());
    }

    @Test
    public void setUuidAcceptsNullForExplicitClear() {
        Account ua = new Account(3);
        ua.setUuid(UUID.randomUUID());
        assertNotNull(ua.getUuid());
        ua.setUuid(null);
        assertNull(ua.getUuid());
    }

    @Test
    public void uuidIsIndependentOfIntegerId() {
        // The integer id is the wire-protocol identity; the UUID is a
        // SOAP-API-only identifier and must be settable without touching
        // the integer space.
        Account ua = new Account(42);
        UUID u = UUID.randomUUID();
        ua.setUuid(u);
        assertEquals(42, ua.getId());
        assertEquals(u, ua.getUuid());
    }

    @Test
    public void distinctAccountsCanHoldDistinctUuids() {
        Account a = new Account(1);
        Account b = new Account(2);
        a.setUuid(UUID.randomUUID());
        b.setUuid(UUID.randomUUID());
        assertNotEquals(a.getUuid(), b.getUuid());
    }
}
