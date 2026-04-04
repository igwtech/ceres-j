package server.gameserver;

import java.util.Iterator;
import java.util.LinkedList;

import server.gameserver.packets.server_tcp.GlobalChat_TCP;

public class ChatManager {	//TODO: complete chanmanager

	// the muting of all channels except the custom channel is handled
	// client-side
	// enumeration of the channels to make reading the code easier
	// TODO: find the correct channel numbers
	public static final int ZONE 			= 1;
	public static final int FRACTION		= 2;
	public static final int ALLY			= 4;
	public static final int TRADE_NC		= 8;
	public static final int TRADE_MB		= 16;
	public static final int TRADE_TH		= 32;
	public static final int TRADE_DOY		= 64;
	public static final int RUNNERSERV_NC	= 128;
	public static final int RUNNERSERV_MB	= 256;
	public static final int RUNNERSERV_TH	= 512;
	public static final int RUNNERSERV_DOY	= 1024;
	public static final int TEAMSEARCH_10	= 2048;
	public static final int TEAMSEARCH_30	= 4096;
	public static final int TEAMSEARCH_50	= 8192;
	public static final int TEAMSEARCH_70	= 16384;
	public static final int CLANSEARCH		= 32768;
	public static final int HELP			= 65536;
	public static final int OOC				= 131072;
	
	private static LinkedList<Player> 	zoneListener			= new LinkedList<Player>();
	private static LinkedList<Player> 	fractionListener		= new LinkedList<Player>();
	private static LinkedList<Player> 	allyListener			= new LinkedList<Player>();
	private static LinkedList<Player> 	tradeNCListener			= new LinkedList<Player>();
	private static LinkedList<Player> 	tradeTHListener			= new LinkedList<Player>();
	private static LinkedList<Player> 	tradeMBListener			= new LinkedList<Player>();
	private static LinkedList<Player> 	tradeDOYListener		= new LinkedList<Player>();
	private static LinkedList<Player> 	runnerservNCListener	= new LinkedList<Player>();
	private static LinkedList<Player> 	runnerservTHListener	= new LinkedList<Player>();
	private static LinkedList<Player> 	runnerservMBListener	= new LinkedList<Player>();
	private static LinkedList<Player> 	runnerservDOYListener	= new LinkedList<Player>();
	private static LinkedList<Player> 	teamsearch10Listener	= new LinkedList<Player>();
	private static LinkedList<Player> 	teamsearch30Listener	= new LinkedList<Player>();
	private static LinkedList<Player> 	teamsearch50Listener	= new LinkedList<Player>();
	private static LinkedList<Player> 	teamsearch70Listener	= new LinkedList<Player>();
	private static LinkedList<Player> 	clansearchListener		= new LinkedList<Player>();
	private static LinkedList<Player> 	helpListener			= new LinkedList<Player>();
	private static LinkedList<Player> 	oocListener				= new LinkedList<Player>();
	
	public static void changedListening(Player pl, int channels){
		
		// during first sync the Client sends the channels he wants to listen to
		// when logging out the channels are saved clientside only
		if(pl.getChannels() == 0){
			pl.setChannels(channels);
			
			// TODO: take care that it will be sent to the right zone/ally/fraction!
			if((channels & ZONE) != 0){
				zoneListener.add(pl);
			}
			
			if((channels & FRACTION) != 0){
				fractionListener.add(pl);
			}
			
			if((channels & ALLY) != 0){
				allyListener.add(pl);
			}
			
			if((channels & TRADE_NC) != 0){
				tradeNCListener.add(pl);
			}
			
			if((channels & TRADE_MB) != 0){
				tradeMBListener.add(pl);
			}
			
			if((channels & TRADE_TH) != 0){
				tradeTHListener.add(pl);
			}
			
			if((channels & TRADE_DOY) != 0){
				tradeDOYListener.add(pl);
			}
			
			if((channels & RUNNERSERV_NC) != 0){
				runnerservNCListener.add(pl);
			}
			
			if((channels & RUNNERSERV_MB) != 0){
				runnerservMBListener.add(pl);
			}
			
			if((channels & RUNNERSERV_TH) != 0){
				runnerservTHListener.add(pl);
			}
			
			if((channels & RUNNERSERV_DOY) != 0){
				runnerservDOYListener.add(pl);
			}
			
			if((channels & TEAMSEARCH_10) != 0){
				teamsearch10Listener.add(pl);
			}
			
			if((channels & TEAMSEARCH_30) != 0){
				teamsearch30Listener.add(pl);
			}
			
			if((channels & TEAMSEARCH_50) != 0){
				teamsearch50Listener.add(pl);
			}
			
			if((channels & TEAMSEARCH_70) != 0){
				teamsearch70Listener.add(pl);
			}
			
			if((channels & CLANSEARCH) != 0){
				clansearchListener.add(pl);
			}
			
			if((channels & HELP) != 0){
				helpListener.add(pl);
			}
			
			if((channels & OOC) != 0){
				oocListener.add(pl);
			}
		}
		else{
			if(pl.getChannels() != channels){
				boolean remove = false;
				int diff = 0;
				if(pl.getChannels() > channels){
					diff = pl.getChannels() - channels;
					remove = true;
				}
				else{
					diff = channels - pl.getChannels();
					remove = false;
				}
			
				if((diff == ZONE) & !remove)
					zoneListener.add(pl);
				else if((diff == ZONE) & remove)
					zoneListener.remove(pl);
			
				if((diff == FRACTION) & !remove)
					fractionListener.add(pl);
				else if((diff == FRACTION) & remove)
					fractionListener.remove(pl);
			
				if((diff == ALLY) & !remove)
					allyListener.add(pl);
				else if((diff == ALLY) & remove)
					allyListener.remove(pl);
			
				if((diff == TRADE_NC) & !remove)
					tradeNCListener.add(pl);
				else if((diff == TRADE_NC) & remove)
					tradeNCListener.remove(pl);
				
				if((diff == TRADE_MB) & !remove)
					tradeMBListener.add(pl);
				else if((diff == TRADE_MB) & remove)
					tradeMBListener.remove(pl);
			
				if((diff == TRADE_TH) & !remove)
					tradeTHListener.add(pl);
				else if((diff == TRADE_TH) & remove)
					tradeTHListener.remove(pl);
						
				if((diff == TRADE_DOY) & !remove)
					tradeDOYListener.add(pl);
				else if((diff == TRADE_DOY) & remove)
					tradeDOYListener.remove(pl);
			
				if((diff  == RUNNERSERV_NC) & !remove)
					runnerservNCListener.add(pl);
				else if((diff  == RUNNERSERV_NC) & remove)
					runnerservNCListener.remove(pl);
			
				if((diff == RUNNERSERV_MB) & !remove)
					runnerservMBListener.add(pl);
				else if((diff == RUNNERSERV_MB) & remove)
					runnerservMBListener.remove(pl);
			
				if((diff == RUNNERSERV_TH) & !remove)
					runnerservTHListener.add(pl);
				else if((diff == RUNNERSERV_TH) & remove)
					runnerservTHListener.remove(pl);
			
				if((diff == RUNNERSERV_DOY) & !remove)
					runnerservDOYListener.add(pl);
				else if((diff == RUNNERSERV_DOY) & remove)
					runnerservDOYListener.remove(pl);
				
				if((diff == TEAMSEARCH_10) & !remove)
					teamsearch10Listener.add(pl);
				else if((diff == TEAMSEARCH_10) & remove)
					teamsearch10Listener.remove(pl);
			
				if((diff == TEAMSEARCH_30) & !remove)
					teamsearch30Listener.add(pl);
				else if((diff == TEAMSEARCH_30) & remove)
					teamsearch30Listener.remove(pl);
			
				if((diff == TEAMSEARCH_50) & !remove)
					teamsearch50Listener.add(pl);
				else if((diff == TEAMSEARCH_50) & remove)
					teamsearch50Listener.remove(pl);
			
				if((diff == TEAMSEARCH_70) & !remove)
					teamsearch70Listener.add(pl);
				else if((diff == TEAMSEARCH_70) & remove)
					teamsearch70Listener.remove(pl);
			
				if((diff == CLANSEARCH) & !remove)
					clansearchListener.add(pl);
				else if((diff == CLANSEARCH) & remove)
					clansearchListener.remove(pl);
			
				if((diff == HELP) & !remove)
					helpListener.add(pl);
				else if((diff == HELP) & remove)
					helpListener.remove(pl);
			
				if((diff == OOC) & !remove)
					oocListener.add(pl);
				else if((diff == OOC) & remove)
					oocListener.remove(pl);
			}
		}
	}
	
	// handles messages, checks for listeners, sends messages to players
	public static void NewMessage(Player sender, String message, int channel){
		// TODO: the channelnumbers seem to be different from the ones above
		LinkedList<Player> playerList = PlayerManager.getPlayers();
		
		switch(channel){
		case ZONE:{
			for (Iterator<Player> i = zoneListener.iterator(); i.hasNext();) {
				Player pl = i.next();
				if (pl != sender)
					pl.send(new GlobalChat_TCP(message, sender, channel));
			}
			break;
		}
		case FRACTION:{
			for (Iterator<Player> i = fractionListener.iterator(); i.hasNext();) {
				Player pl = i.next();
				if (pl != sender) 
					pl.send(new GlobalChat_TCP(message, sender, channel));
			}
			break;
		}
		case ALLY:{
			break;
		}
		case TRADE_NC:{
			break;
		}
		case TRADE_MB:{
			break;
		}
		case TRADE_TH:{
			break;
		}
		case TRADE_DOY:{
			break;
		}
		case RUNNERSERV_NC:{
			break;
		}
		case RUNNERSERV_MB:{
			break;
		}
		case RUNNERSERV_TH:{
			break;
		}
		case RUNNERSERV_DOY:{
			break;
		}
		case TEAMSEARCH_10:{
			break;
		}
		case TEAMSEARCH_30:{
			break;
		}
		case TEAMSEARCH_50:{
			break;
		}
		case TEAMSEARCH_70:{
			break;
		}
		case CLANSEARCH:{
			break;
		}
		case HELP:{
			break;
		}
		case OOC:{
			for (Iterator<Player> i = oocListener.iterator(); i.hasNext();) {
				Player pl = i.next();
				if (pl != sender)
					pl.send(new GlobalChat_TCP(message, sender, channel));
			}
			break;
		}
		default:{			
			for (Iterator<Player> i = playerList.iterator(); i.hasNext();) {
				Player pl = i.next();
				if (pl != sender)
					pl.send(new GlobalChat_TCP(message, sender, channel));
			}			
			break;
		}
		}
	}
}