package server.interfaces;

import server.infoserver.InfoServerConnection;

public interface InfoEvent extends Event {

	void execute(InfoServerConnection isc);
}
