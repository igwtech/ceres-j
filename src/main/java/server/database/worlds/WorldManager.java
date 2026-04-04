package server.database.worlds;

import java.io.InputStream;
import java.util.TreeMap;

import server.database.IniReader;
import server.exceptions.StartupException;
import server.tools.Out;
import server.tools.VirtualFileSystem;

public class WorldManager {

	private static TreeMap<Integer, World> weList = new TreeMap<Integer, World>();
	
	public static void init() throws StartupException {
		InputStream data = VirtualFileSystem.getFileInputStream("worlds\\worlds.ini");
		if (data == null)
			throw new StartupException("Cannt find worlds\\worlds.ini in the Client Folder");
		IniReader ir = new IniReader(data);
		while (true) {
			String[] tokens = ir.getTokens();
			if ((tokens.length > 2) && (tokens[0].equals("set"))) {
				World we = new World(tokens);
				weList.put(new Integer(we.getID()), we);
			} else {
				if (ir.isEof())
					break;
			}
		}
		ir.close();
		Out.writeln(Out.Info, "Loaded " + weList.size() + " World IDs");
	}

	public static String getWorldname(int location) {
		if(weList.get(location) == null)
			return null;
		else
			return weList.get(new Integer(location)).getName();
	}
}
