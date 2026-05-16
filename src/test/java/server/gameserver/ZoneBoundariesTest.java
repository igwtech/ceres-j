package server.gameserver;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the sector-seam entry-point mirror
 * ({@link ZoneBoundaries#mirrorEntryPosition}).
 *
 * <p>Algorithm + constants are TinNS-authoritative (NC1 emulator
 * {@code PWorld::GetVhcZoningDestination} + the static PWorld zone
 * limits — identical for every NC world incl. plaza city sectors).
 * Ceres MISC coords = raw wire uint16 − 32000.
 */
public class ZoneBoundariesTest {

    @Test
    public void constantsMatchTinnsLimits() {
        // wire 0x4700 / 0xb300 OUT, 0x4a00 / 0xb000 IN, −32000.
        assertEquals(-13824, ZoneBoundaries.OUT_LO);
        assertEquals(+13824, ZoneBoundaries.OUT_HI);
        assertEquals(-13056, ZoneBoundaries.IN_LO);
        assertEquals(+13056, ZoneBoundaries.IN_HI);
        // Sector centre is wire 0x7D00 = 32000 → MISC 0; the OUT
        // band is symmetric about it.
        assertEquals(ZoneBoundaries.OUT_HI, -ZoneBoundaries.OUT_LO);
        assertEquals(ZoneBoundaries.IN_HI, -ZoneBoundaries.IN_LO);
    }

    @Test
    public void insideSectorReturnsNull() {
        // Mid-sector position — not an edge walk; coords untouched.
        assertNull(ZoneBoundaries.mirrorEntryPosition(0, 0, 500));
        assertNull(ZoneBoundaries.mirrorEntryPosition(
                ZoneBoundaries.OUT_LO + 1,
                ZoneBoundaries.OUT_HI - 1, -2000));
    }

    @Test
    public void crossLowXMirrorsToHighIn() {
        // Player walked off the low-X edge → enters dest at the
        // high-X IN-limit; Y and Z preserved.
        int[] e = ZoneBoundaries.mirrorEntryPosition(
                ZoneBoundaries.OUT_LO - 100, 4200, -777);
        assertNotNull(e);
        assertEquals(ZoneBoundaries.IN_HI, e[0]);
        assertEquals(4200, e[1]);
        assertEquals(-777, e[2]);
    }

    @Test
    public void crossHighXMirrorsToLowIn() {
        int[] e = ZoneBoundaries.mirrorEntryPosition(
                ZoneBoundaries.OUT_HI + 5, -1234, 99);
        assertNotNull(e);
        assertEquals(ZoneBoundaries.IN_LO, e[0]);
        assertEquals(-1234, e[1]);
        assertEquals(99, e[2]);
    }

    @Test
    public void crossLowYMirrorsToHighIn() {
        int[] e = ZoneBoundaries.mirrorEntryPosition(
                3000, ZoneBoundaries.OUT_LO - 1, 12);
        assertNotNull(e);
        assertEquals(3000, e[0]);
        assertEquals(ZoneBoundaries.IN_HI, e[1]);
        assertEquals(12, e[2]);
    }

    @Test
    public void crossHighYMirrorsToLowIn() {
        int[] e = ZoneBoundaries.mirrorEntryPosition(
                -3000, ZoneBoundaries.OUT_HI + 50, 8);
        assertNotNull(e);
        assertEquals(-3000, e[0]);
        assertEquals(ZoneBoundaries.IN_LO, e[1]);
        assertEquals(8, e[2]);
    }

    @Test
    public void exactlyOnOutLimitCounts() {
        // TinNS uses <= / >= so the limit itself triggers.
        int[] e = ZoneBoundaries.mirrorEntryPosition(
                ZoneBoundaries.OUT_LO, 0, 0);
        assertNotNull(e);
        assertEquals(ZoneBoundaries.IN_HI, e[0]);
    }

    @Test
    public void cornerCrossRemapsBothAxes() {
        // Diagonal corner exit — both axes beyond OUT; both remap.
        int[] e = ZoneBoundaries.mirrorEntryPosition(
                ZoneBoundaries.OUT_HI + 1,
                ZoneBoundaries.OUT_LO - 1, 321);
        assertNotNull(e);
        assertEquals(ZoneBoundaries.IN_LO, e[0]);
        assertEquals(ZoneBoundaries.IN_HI, e[1]);
        assertEquals(321, e[2]);
    }
}
