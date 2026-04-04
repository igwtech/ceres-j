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
		if(zoneList.get(id) == null)
			return zoneList.get(1);
		else
			return zoneList.get(id);
	}
	
	public static void stop(){
		
	}
}
