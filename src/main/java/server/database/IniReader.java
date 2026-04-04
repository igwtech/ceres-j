package server.database;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class IniReader {

	private BufferedReader br;
	private char currentchar;

	public IniReader(InputStream is) {
		this.br = new BufferedReader(new InputStreamReader(is));
	}

	public String[] getTokens() {
		ArrayList<String> tokenlist = new ArrayList<String>();
		String token = new String();
		try {
			getnextChar();
			while (true) {
					while (Character.isWhitespace(currentchar) && (currentchar != '\n'))
						getnextChar();
					if (currentchar == '\n') {
						break;
					} else if (currentchar == '/') {
						getnextChar();
						if (currentchar == '/') { //ignore rest of line
							while(currentchar != '\n') {
								getnextChar();
							}
						} else if (currentchar == '*') { //ignore until */
							getnextChar();
							while (true) {
								if (currentchar != '*') {
									getnextChar();
									continue;
								}
								getnextChar();
								if (currentchar != '/') {
									continue;
								}
								getnextChar();
								break;
							}
						} else {
							token += '/';
							while (!Character.isWhitespace(currentchar)) {
								token += currentchar;
								getnextChar();
							}
						}
					} else if (currentchar == '"') {
						getnextChar();
						while (currentchar != '"') {
							token += currentchar;
							getnextChar();
						}
						getnextChar();
					} else {
						while (!Character.isWhitespace(currentchar)) {
							token += currentchar;
							getnextChar();
						}
					}
				if (token.length() > 0)
					tokenlist.add(token);
				token = new String();
			}
		} catch (IOException e) {
		}
		if (token.length() > 0)
			tokenlist.add(token);
		int length = 0;
		for (int i = 0; i < tokenlist.size(); i++) {
			String t = (String) tokenlist.get(i);
			if (!t.equals("|") && !t.equals(";")) {
				length++;
			}
		}
		if (length == 0 && !isEof())
			return getTokens();
		String[] res = new String[length];
		length = 0;
		for (int i = 0; i < tokenlist.size(); i++) {
			String t = (String) tokenlist.get(i);
			if (!t.equals("|") && !t.equals(";")) {
				res[length] = (String) tokenlist.get(i);
				length++;
			}
		}
		return res;
	}

	private void getnextChar() throws IOException {
		char[] ca = new char[1];
		if (br.read(ca) == -1) throw new IOException();
		if (ca[0] == '\r')
			if (br.read(ca) == -1) throw new IOException();
		currentchar = ca[0];
	}

	public void close() {
		try {
			br.close();
		} catch (IOException e) {
		}
	}

	public boolean isEof() {
		try {
			return !br.ready();
		} catch (IOException e) {
			return true;
		}
	}

}
