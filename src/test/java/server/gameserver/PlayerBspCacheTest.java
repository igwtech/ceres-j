package server.gameserver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Unit tests for the per-Player BSP-load cache added in task #253.
 *
 * <p>Retail empirically emits {@code 0x83/0x0d LoadingBegin} ONLY
 * when the destination BSP is being loaded for the first time —
 * cross-OUT or re-cross to a cached BSP gets only the bare
 * {@code 0x83/0x0c Location}. Ceres-J pre-#253 always emitted
 * both; that's the hypothesised root cause for #208 "SYNCHRONIZING
 * INTO DUNGEON" hangs. {@link Player#hasLoadedBsp} +
 * {@link Player#markBspLoaded} let cross handlers gate the
 * LoadingBegin on first-load.
 */
public class PlayerBspCacheTest {

    @Test
    public void freshPlayerHasNothingLoaded() {
        Player pl = PacketTestFixture.newPlayer();
        assertFalse("freshly-constructed Player has no BSPs cached",
                pl.hasLoadedBsp("plaza/plaza_p1"));
        assertFalse("any path returns false on a fresh Player",
                pl.hasLoadedBsp("startmissions/reaktor"));
    }

    @Test
    public void markBspLoadedMakesItHasLoadedBsp() {
        Player pl = PacketTestFixture.newPlayer();
        pl.markBspLoaded("plaza/plaza_p1");
        assertTrue("after markBspLoaded the path reports loaded",
                pl.hasLoadedBsp("plaza/plaza_p1"));
        // Different path is still NOT loaded.
        assertFalse("other paths remain not-loaded",
                pl.hasLoadedBsp("startmissions/reaktor"));
    }

    @Test
    public void markBspLoadedIsIdempotent() {
        Player pl = PacketTestFixture.newPlayer();
        pl.markBspLoaded("plaza/plaza_p1");
        pl.markBspLoaded("plaza/plaza_p1");
        pl.markBspLoaded("plaza/plaza_p1");
        assertTrue(pl.hasLoadedBsp("plaza/plaza_p1"));
    }

    @Test
    public void markBspLoadedHandlesMultiplePaths() {
        Player pl = PacketTestFixture.newPlayer();
        pl.markBspLoaded("plaza/plaza_p1");
        pl.markBspLoaded("startmissions/reaktor");
        pl.markBspLoaded("citysewer/peppersewer");
        assertTrue(pl.hasLoadedBsp("plaza/plaza_p1"));
        assertTrue(pl.hasLoadedBsp("startmissions/reaktor"));
        assertTrue(pl.hasLoadedBsp("citysewer/peppersewer"));
    }

    @Test
    public void nullAndEmptyAreSafeNoOps() {
        Player pl = PacketTestFixture.newPlayer();
        pl.markBspLoaded(null);
        pl.markBspLoaded("");
        assertFalse(pl.hasLoadedBsp(null));
        assertFalse(pl.hasLoadedBsp(""));
        // And valid mark still works after.
        pl.markBspLoaded("plaza/plaza_p1");
        assertTrue(pl.hasLoadedBsp("plaza/plaza_p1"));
    }

    @Test
    public void pathsAreCaseSensitive() {
        // BSP paths match the wire ASCII string exactly. Retail
        // emits lowercase ("plaza/plaza_p1") — if a caller passes
        // a differently-cased variant, that's a different BSP for
        // our purposes. This pin catches accidental case-folding
        // in a future refactor.
        Player pl = PacketTestFixture.newPlayer();
        pl.markBspLoaded("plaza/plaza_p1");
        assertFalse("BSP cache is case-sensitive",
                pl.hasLoadedBsp("Plaza/Plaza_p1"));
        assertFalse("BSP cache is case-sensitive (upper)",
                pl.hasLoadedBsp("PLAZA/PLAZA_P1"));
    }

    @Test
    public void cacheIsPerPlayer() {
        // Two Players in the same JVM must not share the cache —
        // it's per-session state.
        Player pl1 = PacketTestFixture.newPlayer();
        Player pl2 = PacketTestFixture.newPlayer();
        pl1.markBspLoaded("plaza/plaza_p1");
        assertTrue(pl1.hasLoadedBsp("plaza/plaza_p1"));
        assertFalse("pl2 must NOT see pl1's marks",
                pl2.hasLoadedBsp("plaza/plaza_p1"));
    }
}
