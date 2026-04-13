package server.gameserver;

import java.util.Iterator;
import java.util.TreeMap;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.packets.server_udp.*;

public class Zone extends Thread{ //TODO: making a thread out of that class would be better

	private TreeMap<Integer, Player> playerList = new TreeMap<Integer, Player>(); //Players currently in the Zone
	//private TreeMap<Integer, Item> ItemList = new TreeMap<Integer, Item>();
	private TreeMap<Integer, NPC> NPCList	= new TreeMap<Integer, NPC>();
	private int zoneId;
	private String location;
	
	public Zone(int id, String loc){
		zoneId = id;
		location = new String(loc);
		// Register a "phantom NPC" at id 0xAB so the raw 0x1b
		// object-position heartbeat has a lookup target: the modern
		// client's world-alive watchdog expects broadcasts → client
		// fires RequestWorldInfo for each advertised id → server
		// replies with WorldNPCInfo. If getNPC(0xAB) returns null we
		// silently drop the query and the client retries until
		// timeout. See ObjectPositionBroadcast.java.
		synchronized (NPCList) {
			// Args: (x, y, z, hp, armor, type, ID). Position mirrors
			// ObjectPositionBroadcast (30000, 0, 30000). The id must
			// match the low+high bytes written at offsets 1 and 2 of
			// the raw 0x1b broadcast: 0x1AB (low=0xAB, high=0x01).
			// Client reads both bytes, so a single-byte id like 0xAB
			// gets queried as 0x01AB (the high byte at offset 2 is
			// not a "class marker constant" as we first thought — it's
			// the id's high byte, always 0x01 in retail because retail
			// NPC ids live in 0x101..0x1FF).
			NPCList.put(0x01AB, new NPC(30000, 0, 30000, 100, 0, 1, 0x01AB));
		}
	}
	
	public void loadZone(){ //TODO: access DB
		
	}
	
	public void saveZone(){ //TODO: access DB
		
	}
	
	public String getWorldname() {
		return location;
	}
	
	public NPC getNPC(int ID){
		synchronized(NPCList){
			if(NPCList.containsKey(ID)){
				return NPCList.get(ID);
			}
			else
				return null;
		}
	}
	
	public Player getPlayer(int ID){
		synchronized(playerList){
			if(playerList.containsKey(ID)){
				return playerList.get(ID);
			}
			else
				return null;
		}
	}
	public void localChat(Player pl, String text) { // TODO: only players near you should recieve this
		synchronized(playerList){
			int mapId = pl.getMapID();
			Iterator<Integer> plkeySetIt = playerList.keySet().iterator();
			while(plkeySetIt.hasNext()){
				int i = plkeySetIt.next();
				if(i != mapId){
					Player reciever = playerList.get(i);
					reciever.send(new LocalChatMessage(reciever, text, mapId));
				}
			}
		}
	}
	
	public void registerPlayer(Player pl) {
		synchronized(playerList){
			for(int i = 1; i < 256; i++){
				if(!playerList.containsKey(i)){
					pl.setMapID(i);
					playerList.put(i, pl);
//					sendNPCsinZone(pl); will be done in SyncUPD
					break;
				}
			}
		}
	}
	
	public void unregisterPlayer(Player pl) {
		synchronized(playerList){
			playerList.remove(pl.getMapID());
		}
	}

	public void useitem(int id, Player pl) {
		pl.send(new OpenDoor(id, pl));
		// in some future far far away, we will check permissions here
		/*if ((id & 0x000000ff) != 0) { //door // TODO not 100% correct 
			pl.send(new OpenDoor(id, pl));

		} else {
			
		}*/
	}
	
	// adds a new NPC to  the Zone
	public void addNPCtoZone(int x, int y, int z, int hp, int type, int armor){
		//int id = 0;
		synchronized(NPCList){
			for(int i = 257; i < 510; i++){// Npcs always got an 0xXX01 id
				if(!NPCList.containsKey(i)){
					NPCList.put(i, new NPC(x, y, z, hp, armor, type, i));
					//id = i;
					break;
				}
			}
		}
		sendNPCsinZone();		
	}
	
	// adds a new Object to a Zone	
	public void addObjecttoZone(){
		
	}
	
	// removes an Object from a Zone
	public void removeObjectfromZone(){
		
	}
	
	// sends the Items currently in the Zone
	public void sendObjectsinZone(){
//		 for every player -> senNPCsinZone(pl);
	}
	
	// after someone syncs to zone send him the items we got here...
	public void sendObjectsinZone(Player pl){
		
	}
	
	// sends the NPCs currently in a Zone to all Players
	public void sendNPCsinZone(){ //TODO: needs heavy optimizing!
		synchronized(this){
			if(!NPCList.isEmpty()){
				Iterator<Integer> i = NPCList.keySet().iterator();
				if(!playerList.isEmpty()){
					while(i.hasNext()){
						Iterator<Integer> playerit = playerList.keySet().iterator();
						while(playerit.hasNext()){
							sendNPCinZone(playerList.get(playerit.next()), NPCList.get(i.next()));
						}
					}
				}
			}
		}
	}
	
	// sends the NPCs in the Zone to one specific player pl
	public void sendNPCsinZone(Player pl){
		synchronized(NPCList){
			if(!NPCList.isEmpty()){
				Iterator<Integer> i = NPCList.keySet().iterator();
				while(i.hasNext()){
					sendNPCinZone(pl, NPCList.get(i.next()));
				}
			}
		}
	}
	
	// sends the NPC npc to the Player pl
	public void sendNPCinZone(Player pl, NPC npc){
		pl.send(new SendPresentWorldID(pl, npc));
	}
	
	// sends the position of all the other Players in the Zone to pl
	public void sendPlayersPosinZone(Player pl){ //TODO: find out when this really has to be send
		int mapId = pl.getMapID();
		PlayerCharacter pc = null;
		synchronized(playerList){
			Iterator<Integer> plSetIt = playerList.keySet().iterator();
			while(plSetIt.hasNext()){
				int i = plSetIt.next();
				if(i != mapId){
					pc = playerList.get(i).getCharacter();
					pl.send(new PlayerPositionUpdate(pl, pc, i)); // Smovement
				}
			}
		}
	}
	
	
	
	
	// sends the players currently in a Zone to one player
	public void sendPlayersinZone(Player pl){
		int mapId = pl.getMapID();
		PlayerCharacter pc = null;
		synchronized(playerList){
			Iterator<Integer> plSetIt = playerList.keySet().iterator();
			while(plSetIt.hasNext()){
				int i = plSetIt.next();
				if(i != mapId){
					pc = playerList.get(i).getCharacter();
					pl.send(new PlayerPositionUpdate(pl, pc, i));
					pl.send(new LongPlayerInfo(pl, pc, i));
				}
			}
		}
	}
	
	// send the new player pl to the others
	public void sendnewPlayerinZone(Player pl){
		int mapId = pl.getMapID();
		PlayerCharacter pc = pl.getCharacter();
		synchronized(playerList){
			Iterator<Integer> plSetIt = playerList.keySet().iterator();
			while(plSetIt.hasNext()){
				int i = plSetIt.next();
				if(i != mapId){
					Player reciever = playerList.get(i);
					reciever.send(new PlayerPositionUpdate(reciever, pc, mapId));
					reciever.send(new LongPlayerInfo(reciever, pc, mapId));
				}
			}
		}
	}
	
	// sends the movement of pl to the others in zone
	public void sendPlayerMovement(Player pl){
		int mapId = pl.getMapID();
		PlayerCharacter pc = pl.getCharacter();
		synchronized(playerList){
			Iterator<Integer> plSetIt = playerList.keySet().iterator();
			while(plSetIt.hasNext()){
				int i = plSetIt.next();
				if(i != mapId){
					Player reciever = playerList.get(i);
					reciever.send(new SMovement(reciever, pc, mapId));
					reciever.send(new PlayerPositionUpdate(reciever, pc, mapId));
					//could be that this has to be exchanged with playerposupdate in
					//case the player status hasnt changed since last update
				}
			}
		}
	}
	
	// TODO: all the position update stuff
}
