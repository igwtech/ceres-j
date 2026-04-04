package server.infoserver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import server.exceptions.StartupException;
import server.tools.Out;

public final class InfoServer extends Thread {

	private static InfoServer instance;
	private static boolean keeprunning;
	private static boolean running;

	public static void init() throws StartupException {
		if (instance == null) {
			Out.writeln(Out.Info, "Starting Info Server");

			try {
				instance = new InfoServer(7000);
			} catch (IOException e) {
				instance = null;
				throw new StartupException("Exception while opening serversocket: " + e.getMessage());
			}
			
			keeprunning = true;
			instance.start();
			Out.writeln(Out.Info, "Info Server started");
		}
	}

	public static void stopServer() {
		if (instance != null) {
			synchronized (instance) {
				Out.writeln(Out.Info, "Stopping Info Server");
				keeprunning = false;
				while (running) {
					try {
						instance.wait(100);
					} catch (InterruptedException e) {
					}
				}
				Out.writeln(Out.Info, "Info Server stopped");
			}
		}
	}

	private ServerSocket serverSocket;

	public InfoServer(int port) throws IOException {
		serverSocket = new ServerSocket(port);
		serverSocket.setSoTimeout(100);
	}
	
	public void run() {
		Out.writeln(Out.Info, "Info Server Listening");
		running = true;
		while (keeprunning) {
			try {
				Socket socket = serverSocket.accept();
				InfoServerConnection connection = new InfoServerConnection(socket);
				connection.setDaemon(true);
				connection.start();
			} catch (IOException e) {}
		}
		synchronized (instance) {
			running = false;
			instance.notifyAll();
		}
	}


}
