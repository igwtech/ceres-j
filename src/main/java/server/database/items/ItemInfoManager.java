package server.database.items;

import java.io.InputStream;
import java.util.TreeMap;

import server.database.DefReader;
import server.exceptions.StartupException;
import server.tools.Out;
import server.tools.VirtualFileSystem;

public class ItemInfoManager {

	private static TreeMap<Integer, ItemInfo> ItemInfoList = new TreeMap<Integer, ItemInfo>();
		
		public static void init() throws StartupException {
			InputStream data = VirtualFileSystem.getFileInputStream("defs\\items.def");
			if (data == null)
				throw new StartupException("Cannt find defs\\items.def in the Client Folder");
			DefReader dr = new DefReader(data);
			while (!dr.isEof()) {
				String[] tokens = dr.getTokens();
				if ((tokens.length > 2) && (tokens[0].equals("setentry"))) {
					ItemInfo itinf = new ItemInfo(tokens);
					ItemInfoList.put(itinf.getID(), itinf);
				} else {
					if (dr.isEof())
						break;
				}
			}
			dr.close();
			
			Out.writeln(Out.Info, "Loaded " + ItemInfoList.size() + " Item IDs");
		}
		
		public static ItemInfo getItemInfo(int type){
			if(ItemInfoList.containsKey(type))
				return ItemInfoList.get(type);
			else
				return null;
		}
	}
