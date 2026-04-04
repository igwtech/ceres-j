package server.webserver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import server.exceptions.StartupException;
import server.tools.Config;
import server.tools.Out;

public final class WebServer extends Thread {

	private static WebServer instance = null;
	private ServerSocket serverSocket;
	private static boolean keeprunning;
	private static boolean running;

	public static void init() throws StartupException {
		if ((instance == null) && (Config.getProperty("StartWebServer").equals("true"))) {
			Out.writeln(Out.Info, "Starting HTTP Server");

			try {
				instance = new WebServer(Integer.parseInt(Config.getProperty("WebServerPort")));
			} catch (IOException e) {
				instance = null;
				throw new StartupException("Exception while opening serversocket: " + e.getMessage());
			}
			
			keeprunning = true;
			instance.start();
			Out.writeln(Out.Info, "HTTP Server started");
		}
	}

	public WebServer(int port) throws IOException {
		serverSocket = new ServerSocket(port);
		serverSocket.setSoTimeout(100);
	}

	public void run() {
		Out.writeln(Out.Info, "HTTP Server Listening");
		running = true;
		while (keeprunning) {
			try {
				Socket socket = serverSocket.accept();
				WebServerConnection connection = new WebServerConnection(socket);
				connection.setDaemon(true);
				connection.setPriority(MIN_PRIORITY);
				connection.start();
			} catch (IOException e) {}
		}
		synchronized (instance) {
			running = false;
			instance.notifyAll();
		}
	}

	public static void stopServer() {
		if (instance != null) {
			synchronized (instance) {
				Out.writeln(Out.Info, "Stopping HTTP Server");
				keeprunning = false;
				while (running) {
					try {
						instance.wait(100);
					} catch (InterruptedException e) {}
				}
				Out.writeln(Out.Info, "HTTP Server stopped");
			}
			instance = null;
		}
	}

}
