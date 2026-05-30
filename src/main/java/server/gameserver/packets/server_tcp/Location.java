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
 * <h3>Wire format (verified against retail 2026-05-24, task #252)</h3>
 *
 * <pre>
 *   fe &lt;size:LE16&gt;          TCP application-layer framing
 *   83 0c                        opcode
 *   &lt;destZoneId LE32&gt;        location id (matches MISC_LOCATION)
 *   &lt;reserved LE32 = 0&gt;      always zero in retail
 *   &lt;spawnIdx LE32&gt;          appplaces row index for the
 *                                 destination's entry point
 *                                 (16 for plaza_p1, 1 for reaktor)
 *   &lt;ASCII bsp_path \0&gt;      destination world (from
 *                                 worldinfo.f2 / Zone.getWorldname)
 * </pre>
 *
 * <h3>Retail capture references</h3>
 *
 * <ul>
 *   <li>Cross-IN to reaktor dungeon (zoneId=1573, spawnIdx=1):
 *       <br><code>fe 24 00 83 0c 25 06 00 00 00 00 00 00 00 01 00 00 00
 *       "startmissions/reaktor\0"</code></li>
 *   <li>Cross-OUT / login spawn to plaza_p1 (zoneId=1, spawnIdx=16):
 *       <br><code>fe 1d 00 83 0c 01 00 00 00 00 00 00 00 10 00 00 00
 *       "plaza/plaza_p1\0"</code></li>
 * </ul>
 *
 * <p>Source: {@code ceres-j/strace/nc2_strace_RETAIL_PORTAL_RETRY3_20260524_100535.pcap}
 * frames 53, 312, 5906. Memory: {@code retail-0x830c-bytes-cracked}.
 *
 * <h3>The {@code spawnIdx} parameter</h3>
 *
 * <p>Pre-#252 Ceres-J always wrote {@code 0} here, which produced
 * a wire mismatch against retail (16 / 1 / …). The
 * {@link #Location(Player)} default constructor still emits
 * {@code 0} for backward compatibility with callers that don't
 * yet have the appplaces lookup wired (login, GM warp, …).
 * Callers that DO know the destination's appplaces row (the
 * portal-cross path via
 * {@link server.gameserver.PortalResolver.Portal#exitWorldEntity})
 * should use {@link #Location(Player, int)}.
 *
 * <p>Empirical impact on the client: unverified but plausible.
 * Worth checking against #208 "SYNCHRONIZING into dungeon" hang.
 */
public class Location extends PacketBuilderTCP {

	/**
	 * Backward-compat constructor — emits {@code spawnIdx=0}.
	 * Use the 2-arg form when the appplaces row is known.
	 */
	public Location(Player pl) {
		this(pl, 0);
	}

	/**
	 * @param pl       the player receiving the Location packet
	 * @param spawnIdx appplaces row index for the destination's
	 *                 entry point (use {@code Portal.exitWorldEntity}
	 *                 for portal crosses; {@code 16} for plaza /
	 *                 city defaults; {@code 0} when unknown).
	 */
	public Location(Player pl, int spawnIdx) {
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
		if (location == 9999) {
			writeInt(1);
		} else {
			writeInt(0);
		}
		// Field 3 (spawnIdx): appplaces row pointing at the
		// destination's spawn coordinate. Pre-#252 always 0
		// (wire-mismatch vs retail's 16 for plaza, 1 for reaktor,
		// etc.); now parameterised. Defaults to 0 for callers
		// that don't yet have a defs lookup.
		writeInt(spawnIdx);
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
