package server.webserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import server.tools.Config;

public final class WebServerConnection extends Thread {

	private static final String ANSWERTEXT = "HTTP/1.1 200 OK\r\n"
		+ "Content-Type: text/html; charset=ISO-8859-1\r\n" 
		+ "\r\n" 
		+ "<HTML>\n"
		+ "<HEAD>\n"
		+ "</HEAD>\n"
		+ "<BODY bgcolor=\"#000000\" text=\"#3388FF\">\n"
		+ "<h1>Welcome to Irata III<h1>\n"
		+ "</BODY>\n"
		+ "<!-- SERVERLIST\n"
		+ "##ServerList##"
		+ "-->\n"
		+ "</HTML>\n"
		+ "\r\n"; 
	
	private Socket socket;

	/**
	 * @param socket
	 */
	public WebServerConnection(Socket socket) {
		this.socket = socket;
	}

	public void run() {
		try {
			InputStream nis = socket.getInputStream();
			OutputStream nos = socket.getOutputStream();

			nis.read(new byte[1024]); // read some request

			nos.write(ANSWERTEXT.replaceAll("##ServerList##", getServerList()).getBytes());
		} catch (IOException e) {
		}
		try {
			socket.close();
		} catch (IOException e) {
		}

	}

	/**
	 * @return
	 */
	private String getServerList() {
		String ip = Config.getServerIP(socket);
		return "1 " + ip + new String ("               ").substring(ip.length()) + " Irata III Server\n";
	}
}
