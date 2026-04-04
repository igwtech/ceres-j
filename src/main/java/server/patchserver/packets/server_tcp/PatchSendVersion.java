package server.patchserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;
import server.tools.Config;

public class PatchSendVersion extends PacketBuilderTCP {
	public PatchSendVersion(int i) {
		super();
		write(new byte[] {0x37, 0x02, 0x00, 0x00});
		writeShort(i);
		write(Integer.parseInt(Config.getProperty("ServerVersion")));
		write(new byte[] {0, 0, 0});
	}

}
