package server.database.modelTextures;

import java.io.InputStream;
import java.util.TreeMap;

import server.database.DefReader;
import server.exceptions.StartupException;
import server.tools.VirtualFileSystem;

public class ModelTextureManager {

	private static TreeMap<Integer, ModelTexture> mteList = new TreeMap<Integer, ModelTexture>();

	public static void init() throws StartupException {
		InputStream data = VirtualFileSystem.getFileInputStream("defs\\modeltextures.def");
		if (data == null)
			throw new StartupException("Cannt find defs\\modeltextures.def in the Client Folder");
		DefReader dr = new DefReader(data);
		while (true) {
			String[] tokens = dr.getTokens();
			if ((tokens.length > 2) && (tokens[0].equals("setentry"))) {
				ModelTexture ce = new ModelTexture(tokens);
				mteList.put(new Integer(ce.getId()), ce);
			} else {
				if (dr.isEof())
					break;
			}
		}
		dr.close();
	}

	public static ModelTexture getEntry(int id) {
		return (ModelTexture) mteList.get(new Integer(id));
	}

}
