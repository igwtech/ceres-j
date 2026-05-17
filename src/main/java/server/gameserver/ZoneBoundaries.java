package server.gameserver;

/**
 * Server-side detection of zone-boundary crossings during natural movement.
 *
 * <p>Retail's "smooth zone walk" (no splash, no CharInfo redelivery) only
 * triggers when the client's position crosses a server-tracked zone edge.
 * The server then sends TCP {@code 0x83 0x0d} + {@code 0x83 0x0c} to load
 * the destination BSP and the client picks up where it left off.
 *
 * <p>Empirical edge coordinates were mined from a 2026-05-02 retail capture
 * of three consecutive walks (pepper_p3 → pepper_p2 → pepper_p1). See
 * {@code tools/mine_zone_edges.py} + {@code docs/zoning_protocol_2026-05-02.md}.
 *
 * <p>The boundary table grows as more captures land. For zones we don't
 * yet have edge data for, the detector returns {@link #NO_TRANSITION}
 * and movement proceeds unchanged.
 */
public final class ZoneBoundaries {

    /** Sentinel returned when the new position is still inside the current zone. */
    public static final int NO_TRANSITION = -1;

    /**
     * Resolve a zone transition for a player whose new coordinates have
     * just been broadcast.
     *
     * @param currentZone the zone the player is presently registered in
     * @param newX        new X (post-32000 offset)
     * @param newY        new Y (post-32000 offset)
     * @param newZ        new Z (post-32000 offset)
     * @return the destination zone_id if the player has crossed a boundary,
     *         else {@link #NO_TRANSITION}
     */
    public static int resolveTransition(int currentZone, int newX, int newY, int newZ) {
        switch (currentZone) {
        case 7: // pepper_p3 — boundary with pepper_p2 at y ≈ -13394 (south-bound)
            if (newY < -13394) return 6;
            return NO_TRANSITION;
        case 6: // pepper_p2 — sandwiched between p3 (north) and p1 (south)
            if (newY > -13394) return 7; // back into p3
            if (newY < -28940) return 5; // forward into p1
            return NO_TRANSITION;
        case 5: // pepper_p1 — boundary with pepper_p2 at y ≈ -28940 (north-bound)
            if (newY > -28940) return 6;
            return NO_TRANSITION;
        default:
            // Unknown zone; no edge data yet.
            return NO_TRANSITION;
        }
    }

    // ── Sector-seam entry-point mirror ──────────────────────────
    //
    // Decoded from TinNS (NC1 emulator): PWorld::GetVhcZoning
    // Destination + the static PWorld zone limits. Edge cross =
    // crossed axis snaps to the opposite sector's IN-limit; other
    // axis + Z + orientation preserved (mirror across the seam).
    //
    // ⚠ SCOPE (2026-05-16, zone_portal_params.md §6): these limits
    // apply ONLY to OUTDOOR WASTELAND TERRAIN worlds (worldId
    // >= 2001), NOT the indexed city sectors plaza/pepper p1..pN.
    // The on-foot city-sector border code path was NOT located in
    // TinNS (only the vehicle/outdoor one), so there are NO
    // authoritative constants for plaza inter-sector crossing.
    // DO NOT apply mirrorEntryPosition() to plaza/pepper p-sector
    // crossings — that was a guess that regressed live testing
    // (splash/stuck). The correct plaza inter-sector entry must be
    // derived from the verbose client log (enabled 2026-05-16:
    // "Old/New Position", "StartPos", "no worldchange entity
    // found", "World Has No StartPos") on a real p1<->p3 cross,
    // not from these terrain constants. Kept here only for the
    // genuine outdoor-terrain (>=2001) case.
    //
    // Raw wire coords are uint16 0..0xFFFF; Ceres MISC_*_COORDINATE
    // is wire-32000 (see Movement.java: readShort()-32000). Sector
    // centre = wire 0x7D00 = 32000 → MISC 0, so a sector spans
    // MISC ≈ [-13824, +13824].
    //
    //   wire 0x4800-0x100 = 0x4700 (OutLo)  → MISC -13824
    //   wire 0x4a00       = 0x4a00 (InLo)   → MISC -13056
    //   wire 0xb200+0x100 = 0xb300 (OutHi)  → MISC +13824
    //   wire 0xb000       = 0xb000 (InHi)   → MISC +13056

    /** MISC-space low OUT limit: crossing below this leaves the
     *  sector on the low side of that axis. */
    public static final int OUT_LO = (0x4800 - 0x100) - 32000; // -13824
    /** MISC-space high OUT limit. */
    public static final int OUT_HI = (0xb200 + 0x100) - 32000; // +13824
    /** MISC-space low IN limit (appear here when entering from the
     *  high side). */
    public static final int IN_LO  = 0x4a00 - 32000;            // -13056
    /** MISC-space high IN limit (appear here when entering from the
     *  low side). */
    public static final int IN_HI  = 0xb000 - 32000;            // +13056

    /** TinNS outdoor worldmap grid bounds: worldId =
     *  {@code mOutdoorBaseWorldId(2001) + mOutdoorWorldIdVIncrement(20)*V + H},
     *  grid 16(H)×11(V) → ids 2001..2216 inclusive
     *  (2001 + 20*(11-1) + (16-1)). */
    public static final int OUTDOOR_WORLDID_MIN = 2001;
    public static final int OUTDOOR_WORLDID_MAX =
            2001 + 20 * (11 - 1) + (16 - 1);   // 2216

    /**
     * True iff {@code worldId} is a WASTELAND OUTDOOR terrain zone
     * (the TinNS coordinate-limit grid, ids 2001..2216). The
     * sector-seam mirror ({@link #mirrorEntryPosition}) is
     * authoritative and validated ONLY for these.
     *
     * <p>Every INDEXED city/sector zone — plaza, pepper, industry,
     * outzone p-sectors, AND Military Base, Techhaven, DoY,
     * Twilight Guardian/Cliff, etc. — has a worldId &lt; 2001 and
     * is therefore excluded: those use a different, still-unknown
     * entry mechanism and applying the mirror there mis-placed the
     * player (regressed live testing), so it stays off for them
     * (their spawn follows the unchanged default path).
     */
    public static boolean isWastelandOutdoor(int worldId) {
        return worldId >= OUTDOOR_WORLDID_MIN
            && worldId <= OUTDOOR_WORLDID_MAX;
    }

    /**
     * True iff {@code worldId} is an INDEXED CITY / SECTOR zone — plaza,
     * pepper, industry, outzone p-sectors and the other named zones
     * whose worldId is below the TinNS outdoor-terrain grid base. This
     * is the exact complement of {@link #isWastelandOutdoor} over the
     * valid positive zone-id space.
     *
     * <p>Retail evidence (RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT pcap,
     * 2026-05-02; {@code docs/zone_portal_params.md} §7): a city↔city
     * walk-cross receives NO server-pushed self-position — neither a
     * {@code 0x03/0x2c} StartPos nor a {@code 0x03/0x1b} self-position.
     * The client self-positions from the destination zone's local
     * {@code .dat} geometry across the sector seam (verified plaza_p1
     * exit {@code (-1057.7,-255.9,2915.3)} → plaza_p3 entry
     * {@code (-1109.1,-255.9,1076.5)}). Ceres must therefore SUPPRESS
     * its self-position push for these crosses (pushing stale
     * source-zone coords is the task #174 "spawn reset to map centre").
     * The wasteland/outdoor mirror ({@link #mirrorEntryPosition},
     * worldId &gt;= 2001) is unaffected.
     *
     * @param worldId committed destination worldId
     * @return {@code true} for an indexed city/sector zone
     *         (0 &lt; worldId &lt; {@value #OUTDOOR_WORLDID_MIN})
     */
    public static boolean isIndexedCitySector(int worldId) {
        return worldId > 0 && worldId < OUTDOOR_WORLDID_MIN;
    }

    /**
     * Compute the player's entry position in the destination sector
     * for an edge-walk crossing, mirroring across the shared seam
     * exactly as TinNS's {@code GetVhcZoningDestination} does.
     *
     * <p>Only the single axis the player actually exceeded is
     * remapped (to the opposite sector's IN-limit); the other
     * horizontal axis and Z are preserved. Returns {@code null}
     * when the player is NOT beyond an OUT-limit on exactly the
     * X or Y axis — i.e. this was not an edge-walk (GenRep, subway,
     * apartment, admin warp, or a coordinate scale we don't model):
     * callers then leave the stored coordinates untouched.
     *
     * @param x source MISC X
     * @param y source MISC Y
     * @param z source MISC Z (preserved)
     * @return {@code {newX,newY,newZ}} or {@code null} if not an
     *         edge-walk
     */
    public static int[] mirrorEntryPosition(int x, int y, int z) {
        int nx = x, ny = y;
        boolean crossed = false;

        if (x <= OUT_LO)      { nx = IN_HI; crossed = true; }
        else if (x >= OUT_HI) { nx = IN_LO; crossed = true; }

        if (y <= OUT_LO)      { ny = IN_HI; crossed = true; }
        else if (y >= OUT_HI) { ny = IN_LO; crossed = true; }

        return crossed ? new int[] { nx, ny, z } : null;
    }

    private ZoneBoundaries() {} // static-only
}
