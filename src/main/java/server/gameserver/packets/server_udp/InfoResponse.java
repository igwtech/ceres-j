package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * InfoResponse (0x03 → 0x23) — session/entity information response.
 *
 * Retail sends two InfoResponse sub-packets in the initial burst:
 *
 * 1. After CharInfo multipart: {@code 20 00 [worldflags LE32]} (6 bytes)
 *    — the ZONE-SECURITY carrier (sub 0x20 zoneInfo). The LE32 is the
 *    destination zone's WORLDFLAGS: bit 0x10 = SECURE sector (client
 *    prints "Secure Sector" and refuses weapon draw). Retail values:
 *    plaza_p1/p2=0x10, pepper=0x00, MC5=0x90, wasteland=0x100,
 *    outzone_station=0x400, dungeons=0x01 — always identical to the
 *    3rd LE32 of TCP 0x83/0x0c Location (40/40 entries, 12 pcaps).
 *    See {@link server.gameserver.PortalResolver#lookupWorldFlags(int)}.
 *
 * 2. Mixed into the zone-data packet: {@code 0e 00 00 00 00 00 00 00 01 00} (10 bytes)
 *    — appears to be session info (0x0e = 14 sub-opcode, trailing 01 00)
 *
 * These are observed in retail capture ACC1_CHAR1 at packet #11 (seq 24)
 * and packet #12 (seq 27).
 *
 * HISTORY (2026-06-12): zoneInfo used to hardcode {@code 20 00 10 00
 * 00 00} — the plaza_p1 (secure) sample from ACC1_CHAR1 treated as a
 * constant. That marked EVERY Ceres zone a Secure Sector, which is
 * what blocked quickbelt weapon draw/holster ("Secure Sector — You
 * can neither draw any weapons here…" in PvP zones where retail says
 * only "Outskirt Sector").
 */
public class InfoResponse extends PacketBuilderUDP1303 {

    /**
     * Zone-security info variant (6 bytes): {@code 20 00
     * [worldflags LE32]} — flags resolved for the player's current
     * zone (MISC_LOCATION). Falls back to 0 (non-secure; the
     * client never defaults to secure) when no character/zone/DB.
     */
    public static InfoResponse zoneInfo(Player pl) {
        int flags = 0;
        if (pl.getCharacter() != null) {
            int location = pl.getCharacter().getMisc(
                    server.database.playerCharacters
                            .PlayerCharacter.MISC_LOCATION);
            if (location != 9999) {
                flags = server.gameserver.PortalResolver
                        .lookupWorldFlags(location);
            }
        }
        return new InfoResponse(pl, new byte[]{0x20, 0x00,
                (byte) (flags & 0xff),
                (byte) ((flags >> 8) & 0xff),
                (byte) ((flags >> 16) & 0xff),
                (byte) ((flags >> 24) & 0xff)});
    }

    /** Session info variant (10 bytes): 0e 00 00 00 00 00 00 00 01 00 */
    public static InfoResponse sessionInfo(Player pl) {
        return new InfoResponse(pl, new byte[]{0x0e, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00});
    }

    /**
     * Pre-transition meta (19 bytes), sent by retail in response to
     * Zoning1 (C->S {@code 0x03/0x22} sub {@code 0x0d}). Format observed
     * against PLAZA_TO_PEPPER_CROSS_DISTRICT capture 2026-05-02 t=144.63s:
     * {@code 04 00 00 00 00 00 00 00 00 00 00 03 00 00 00 [UID LE32]}.
     * Combined with the despawn burst that follows, this triggers the
     * client to send Zoning2 ({@code 0x22} sub {@code 0x03}) which
     * actually starts the BSP swap.
     */
    public static InfoResponse zoneTransitionMeta(Player pl) {
        int uid = pl.getCharacter().getMisc(server.database.playerCharacters.PlayerCharacter.MISC_ID);
        byte[] payload = new byte[]{
            0x04, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x03, 0x00, 0x00, 0x00,
            (byte)(uid & 0xff),
            (byte)((uid >> 8) & 0xff),
            (byte)((uid >> 16) & 0xff),
            (byte)((uid >> 24) & 0xff),
        };
        return new InfoResponse(pl, payload);
    }

    /**
     * Post-transition session info (6 bytes), sent by retail right after
     * the TCP {@code 0x83 0x0c} Location packet. Observed bytes:
     * {@code 0f 00 03 00 01 00}. Different from
     * {@link #sessionInfo(Player)} which fires during initial login.
     */
    public static InfoResponse postTransitionInfo(Player pl) {
        return new InfoResponse(pl, new byte[]{0x0f, 0x00, 0x03, 0x00, 0x01, 0x00});
    }

    private InfoResponse(Player pl, byte[] payload) {
        super(pl);
        write(0x23);  // reliable sub-type: InfoResponse
        write(payload);
    }
}
