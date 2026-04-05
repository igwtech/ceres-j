package server.database.worlds;

import server.tools.Out;

public class World {

	private int id;
	private String name;

	/**
	 * Direct constructor used by the SQLite-backed loader, where the
	 * path has already been normalised (backslashes to forward slashes,
	 * {@code .bsp} stripped).
	 */
	public World(int id, String path) {
		this.id = id;
		this.name = path;
	}

	public World(String[] tokens) {
		int length = tokens.length;
		if (tokens[length-1].equals("|"))
			length--;
		id = Integer.parseInt(tokens[1]);
		String filename = tokens[2];
		filename = filename.substring(0, filename.lastIndexOf(".bsp"));
		if (filename.startsWith(".\\worlds\\")) {
			name = filename.substring(9).replace('\\', '/');
		} else if (filename.startsWith(".\\")) {
			name = filename.substring(2).replace('\\', '/');
		} else {
			Out.writeln(Out.Error, "Unknown worlds.ini entry: " + tokens[2]);
			name = "";
		}
	}

	public int getID() {
		return id;
	}

	public String getName() {
		return name;
	}
}
