package server.gameserver;

import java.util.TreeMap;
import server.database.worlds.WorldManager;

public class ZoneManager {	//TODO implement Zone Manager
	
	private static TreeMap<Integer, Zone> zoneList = new TreeMap<Integer, Zone>();
	
	public static void init(){
		for(int i = 1; i < 100001; i++){
			String name = WorldManager.getWorldname(i);
			if(name != null)
				zoneList.put(i, new Zone(i,name));
		}
	}
	
	public static Zone getZone(int id){
		Zone z = zoneList.get(id);
		if (z == null) {
			// Fallback to Plaza Sec-1. WARN because this silently
			// relocates the player into a worldType=1 City zone — the
			// client then shows "City Sector / Secure Sector — you can
			// neither draw any weapons here" and blocks weapon-drawing,
			// EVEN if the character actually belongs in a combat sector
			// (Pepper Park / Outzone). A combat-zone char that hits this
			// path is the classic "every sector is secure" symptom. If
			// you see this for a Pepper/Outzone id, the zone simply
			// wasn't registered (missing world_defs row) — fix the data,
			// don't let the fallback mask it.
			server.tools.Out.writeln(server.tools.Out.Warning,
				"ZoneManager.getZone: no zone registered for id=" + id
				+ " — falling back to Plaza Sec-1 (zone 1, SECURE city). "
				+ "Player will be shown as in a secure no-weapon sector.");
			return zoneList.get(1);
		}
		return z;
	}

	/** Snapshot of all zones currently registered. Defensive copy
	 *  so callers can iterate without holding the internal lock. */
	public static java.util.Collection<Zone> getAllZones() {
		return new java.util.ArrayList<>(zoneList.values());
	}
	
	public static void stop(){
		
	}
}
