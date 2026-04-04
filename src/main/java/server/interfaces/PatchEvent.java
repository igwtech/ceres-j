package server.interfaces;

import server.patchserver.PatchServerConnection;

public interface PatchEvent extends Event {

	void execute(PatchServerConnection psc);
}
