package server.gameserver.command.commands;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Covers the numeric branch of {@link ZoneCommand#resolveZone(String)}
 * — the {@code zone} GM command's "bare number = literal world id"
 * dispatch (task #182). Lives in the {@code commands} package because
 * {@code resolveZone} is a package-private internal helper.
 *
 * <p>The <em>name</em> branch delegates to
 * {@link server.database.worlds.WorldManager#resolveByName(String)},
 * which is exhaustively covered (full path / basename / compact short
 * form / case-insensitivity / unknown) by
 * {@code WorldManagerResolveByNameTest} — that test owns the
 * SQLite-backed world-defs fixture and the package-private
 * {@code WorldManager.reset()} lifecycle, so it is not duplicated
 * here.
 */
public class ZoneCommandResolveTest {

    @Test
    public void decimalIdTakenLiterally() {
        // A bare number is the world id even with no world_defs row
        // loaded (admin may target a not-yet-loaded zone).
        assertEquals(42, ZoneCommand.resolveZone("42"));
        assertEquals(1, ZoneCommand.resolveZone("1"));
        assertEquals(1573, ZoneCommand.resolveZone("1573"));
    }

    @Test
    public void hexIdTakenLiterally() {
        assertEquals(0x10, ZoneCommand.resolveZone("0x10"));
        assertEquals(0xFF, ZoneCommand.resolveZone("0xff"));
    }

    @Test
    public void nonNumericNonResolvableNameIsMinusOne() {
        // No world_defs loaded → name lookup misses → -1 (not an
        // exception); ZoneCommand turns this into a clean ERROR.
        assertEquals(-1, ZoneCommand.resolveZone("garbage_zone"));
        assertEquals(-1, ZoneCommand.resolveZone(null));
    }
}
