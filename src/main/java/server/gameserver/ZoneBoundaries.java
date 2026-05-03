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

    private ZoneBoundaries() {} // static-only
}
