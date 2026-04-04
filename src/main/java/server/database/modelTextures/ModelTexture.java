package server.database.modelTextures;

public class ModelTexture {

	private int id;
	private int[] headtexture = new int[30];
	private int[] torsotexture = new int[30];
	private int[] legtexture = new int[30];

	public ModelTexture(String[] tokens) {
		int length = tokens.length;
		if (tokens[length-1].equals("|"))
			length--;
		id = Integer.parseInt(tokens[1]);
		for (int i = 0; i < 30; i++) {
			headtexture[i] = Integer.parseInt(tokens[i+2]);
		}
		for (int i = 0; i < 30; i++) {
			torsotexture[i] = Integer.parseInt(tokens[i+32]);
		}
		for (int i = 0; i < 30; i++) {
			legtexture[i] = Integer.parseInt(tokens[i+62]);
		}
	}

	public int getId() {
		return id;
	}

	public boolean isOldFormat() {
		return torsotexture[0] != -1;
	}

	public int findTextureHead(int value) {
		for (int i = 0; i < 30; i++) {
			if (headtexture[i] == value) return i;
		}
		return 0;
	}

	public int findTextureTorso(int value) {
		for (int i = 0; i < 30; i++) {
			if (torsotexture[i] == value) return i;
		}
		return 0;
	}

	public int findTextureLeg(int value) {
		for (int i = 0; i < 30; i++) {
			if (legtexture[i] == value) return i;
		}
		return 0;
	}

	public int getTextureHead(int i) {
		return headtexture[i];
	}

	public int getTextureTorso(int i) {
		return torsotexture[i];
	}

	public int getTextureLeg(int i) {
		return legtexture[i];
	}

}
