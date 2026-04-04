package server.gameserver.internalEvents;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.Player;
import server.interfaces.Event;
import server.interfaces.GameServerEvent;
import server.tools.Out;
import server.tools.Timer;

public abstract class DummyEvent implements GameServerEvent {

	protected long eventTime = Timer.getRealtime();
	
	public long getEventTime() {
		return eventTime;
	}

	public int compareTo(Event o) {
		long diff = eventTime - (o.getEventTime()); 
		if (diff == 0) {
			return 0;
		} else if (diff < 0) {
			return -1;
		} else {
			return 1;
		}
	}
	
	public void execute(GameServerTCPConnection tcp) {
		Out.writeln(Out.Error, "TCPConnection " + tcp.getName() + " executes Event Dummy of " + this.getClass().getName());
	}

	public void execute(Player pl) {
		Out.writeln(Out.Error, "Player " + pl.getName() + " executes Event Dummy of " + this.getClass().getName());
	}

}
