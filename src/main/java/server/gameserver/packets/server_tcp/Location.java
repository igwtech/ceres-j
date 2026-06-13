package server.gameserver.packets.server_tcp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.networktools.PacketBuilderTCP;

/**
 * TCP S→C {@code 0x83/0x0c Location} — the world-load directive
 * that tells the client which BSP to load for its current zone.
 * Sent during login spawn, on portal/zone crosses, and on
 * GM-driven warps.
 *
 * <h3>Wire format (field 3 re-decoded 2026-06-12)</h3>
 *
 * <pre>
 *   fe &lt;size:LE16&gt;          TCP application-layer framing
 *   83 0c                        opcode
 *   &lt;destZoneId LE32&gt;        location id (matches MISC_LOCATION)
 *   &lt;reserved LE32 = 0&gt;      always zero in retail
 *   &lt;worldflags LE32&gt;        per-zone WORLDFLAGS bitmask —
 *                                 bit 0x10 = SECURE sector (no
 *                                 weapon draw), 0x01 = dungeon,
 *                                 0x100 = wasteland, 0x400 =
 *                                 warzone. See
 *                                 PortalResolver.lookupWorldFlags.
 *   &lt;ASCII bsp_path \0&gt;      destination world (from
 *                                 worldinfo.f2 / Zone.getWorldname)
 * </pre>
 *
 * <h3>Retail capture references</h3>
 *
 * <ul>
 *   <li>Cross-IN to reaktor dungeon (zoneId=1573, flags=0x01 dungeon):
 *       <br><code>fe 24 00 83 0c 25 06 00 00 00 00 00 00 00 01 00 00 00
 *       "startmissions/reaktor\0"</code></li>
 *   <li>Login spawn AND walk-crosses into plaza_p1 (zoneId=1,
 *       flags=0x10 secure):
 *       <br><code>fe 1d 00 83 0c 01 00 00 00 00 00 00 00 10 00 00 00
 *       "plaza/plaza_p1\0"</code></li>
 *   <li>Any entry into pepper_p1 (zoneId=5, flags=0 — PvP/outskirt):
 *       <br><code>fe 1f 00 83 0c 05 00 00 00 00 00 00 00 00 00 00 00
 *       "pepper/pepper_p1\0"</code></li>
 * </ul>
 *
 * <p>Sources: {@code PORTAL_RETRY3} frames 53/312/5906,
 * {@code PLAZA_TO_PEPPER_CROSS_DISTRICT}, {@code PLAZA_CROSSZONE}
 * (plaza_p1 walk-cross ×3 all 0x10), {@code LONG_PARTY_A/B}
 * (plaza_p2=0x10), {@code VEHICLE_DRONE} (outzone_station=0x400,
 * terrain=0x100), {@code CREATION_LEVELING} (MC5=0x90, h_14=0x84).
 *
 * <h3>Field 3 history — two misreads</h3>
 *
 * <p>This field was documented as a login-only "spawn_flag", then
 * (task #252) as an "appplaces spawn row index" ("16 for plaza, 1
 * for reaktor" — a coincidence collapse: those values are plaza's
 * 0x10 SECURE bit and reaktor's 0x01 dungeon bit). It is the
 * per-zone worldflags, identical on every entry method, and is one
 * of the two carriers of zone security (the other being UDP
 * {@code 0x03/0x23/0x20} zoneInfo). It is now derived internally
 * from the destination zone — callers no longer pass it.
 */
public class Location extends PacketBuilderTCP {

	public Location(Player pl) {
		int location = pl.getCharacter().getMisc(PlayerCharacter.MISC_LOCATION);
		write(0x83);
		write(0x0c);
		writeInt(location);
		// Field 2 (reserved): retail always emits 0. The legacy
		// `1` for `location==9999` is a Ceres-J special-case that
		// pre-dates the retail capture; harmless because it only
		// fires for the apps/clean/plaza_app_4_c char-selection
		// world which isn't a regular zone. Kept for backward
		// compatibility with prior test expectations + the
		// downstream client paths that may have observed it.
		// (Retail apartments DO set field2=1 with an instance id
		// as field1 — see CREATION_LEVELING apps/canyon_app_1.)
		if (location == 9999) {
			writeInt(1);
		} else {
			writeInt(0);
		}
		// Field 3 (worldflags): per-zone security/type bitmask.
		// 0 for the 9999 char-select world (retail apartments
		// carry flags=0 too).
		writeInt(location == 9999 ? 0
				: server.gameserver.PortalResolver
						.lookupWorldFlags(location));
		// Zone name lookup. Tolerate a null zone (player whose
		// currentZone wasn't initialised, or fresh fixture in tests)
		// by emitting an empty string — the client still gets a
		// complete frame instead of an NPE killing the connection.
		String worldname;
		if (location == 9999) {
			worldname = "apps/clean/plaza_app_4_c";
		} else {
			Zone z = pl.getZone();
			worldname = (z == null || z.getWorldname() == null)
					? "" : z.getWorldname();
		}
		write(worldname.getBytes());
		write(0); //CStyle
	}

}
