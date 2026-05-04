package server.gameserver;

import java.util.Iterator;
import java.util.TreeMap;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.packets.server_udp.*;
import server.tools.Out;

public class Zone extends Thread{ //TODO: making a thread out of that class would be better

	private TreeMap<Integer, Player> playerList = new TreeMap<Integer, Player>(); //Players currently in the Zone
	//private TreeMap<Integer, Item> ItemList = new TreeMap<Integer, Item>();
	private TreeMap<Integer, NPC> NPCList	= new TreeMap<Integer, NPC>();
	private int zoneId;
	private String location;
	
	public Zone(int id, String loc){
		zoneId = id;
		location = new String(loc);
		// Load NPC spawns from the npc_spawns SQLite table. Each NPC's
		// id lives in the retail convention range 0x101–0x1FF so the
		// raw 0x1b position broadcasts and RequestWorldInfo replies
		// reference valid ids.
		java.util.List<NPC> spawns = server.database.NpcSpawnManager.loadForZone(id);
		synchronized (NPCList) {
			for (NPC npc : spawns) {
				NPCList.put(npc.getMapID(), npc);
			}
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

	/**
	 * Return a snapshot of all NPCs in this zone for broadcast iteration.
	 * The returned list is a copy safe for iteration without holding the
	 * NPCList lock.
	 */
	public java.util.List<NPC> getAllNPCs() {
		synchronized (NPCList) {
			return new java.util.ArrayList<>(NPCList.values());
		}
	}

	public int getZoneId() {
		return zoneId;
	}

	/** Snapshot of all players currently registered in the zone.
	 *  Returned as a defensive copy so callers can iterate without
	 *  holding the {@code playerList} lock. */
	public java.util.List<Player> getAllPlayers() {
		synchronized (playerList) {
			return new java.util.ArrayList<>(playerList.values());
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
				if(i == mapId) continue;
				Player reciever = playerList.get(i);
				if (reciever == null || reciever.getUdpConnection() == null) continue;
				reciever.send(new LocalChatMessage(reciever, text, mapId));
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

	/**
	 * Remove player entries whose UDP session has gone away (client
	 * disconnected or never completed the UDP handshake). Heartbeat
	 * events keep a Player's event loop alive by continually updating
	 * lastping, so the 60-second idle timeout in {@link Player#run()}
	 * never fires for dead clients. Without this sweep, broadcast
	 * methods would keep tripping over those stale entries.
	 *
	 * @return number of entries removed.
	 */
	public int reapDeadPlayers() {
		int removed = 0;
		synchronized (playerList) {
			Iterator<java.util.Map.Entry<Integer, Player>> it =
				playerList.entrySet().iterator();
			while (it.hasNext()) {
				java.util.Map.Entry<Integer, Player> e = it.next();
				Player p = e.getValue();
				if (p == null || p.getUdpConnection() == null) {
					Out.writeln(Out.Info,
						"Zone[" + zoneId + "] reaped stale player slot="
						+ e.getKey()
						+ " name="
						+ (p != null && p.getCharacter() != null
							? p.getCharacter().getName() : "?"));
					it.remove();
					removed++;
				}
			}
		}
		return removed;
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

	/** Remove an NPC from this zone by mapID. Returns the removed
	 *  NPC instance or null if it wasn't tracked. */
	public NPC removeNpc(int mapId) {
		synchronized (NPCList) {
			return NPCList.remove(mapId);
		}
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
		if (pl.getUdpConnection() == null) return;
		int mapId = pl.getMapID();
		PlayerCharacter pc = null;
		synchronized(playerList){
			Iterator<Integer> plSetIt = playerList.keySet().iterator();
			while(plSetIt.hasNext()){
				int i = plSetIt.next();
				if(i != mapId){
					Player other = playerList.get(i);
					if (other == null || other.getCharacter() == null) continue;
					pc = other.getCharacter();
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
		if (pc == null) return;
		reapDeadPlayers();
		synchronized(playerList){
			Iterator<Integer> plSetIt = playerList.keySet().iterator();
			while(plSetIt.hasNext()){
				int i = plSetIt.next();
				if(i == mapId) continue;
				Player reciever = playerList.get(i);
				// Skip receivers whose UDP session isn't established yet
				// (stale Player objects from disconnected clients whose
				// event loop is still alive, or fresh logins that haven't
				// completed their first UDP packet). Without this guard,
				// incandgetSessionCounter() NPEs and aborts the broadcast.
				if (reciever == null || reciever.getUdpConnection() == null) continue;
				reciever.send(new PlayerPositionUpdate(reciever, pc, mapId));
				reciever.send(new LongPlayerInfo(reciever, pc, mapId));
			}
		}
	}

	// sends the movement of pl to the others in zone
	public void sendPlayerMovement(Player pl){
		int mapId = pl.getMapID();
		PlayerCharacter pc = pl.getCharacter();
		if (pc == null) return;
		synchronized(playerList){
			Iterator<Integer> plSetIt = playerList.keySet().iterator();
			while(plSetIt.hasNext()){
				int i = plSetIt.next();
				if(i == mapId) continue;
				Player reciever = playerList.get(i);
				if (reciever == null || reciever.getUdpConnection() == null) continue;
				reciever.send(new SMovement(reciever, pc, mapId));
				reciever.send(new PlayerPositionUpdate(reciever, pc, mapId));
				//could be that this has to be exchanged with playerposupdate in
				//case the player status hasnt changed since last update
			}
		}
	}
	
	// TODO: all the position update stuff
}
