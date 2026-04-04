package server.patchserver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import server.exceptions.StartupException;
import server.tools.Out;



public final class PatchServer extends Thread {

	private static PatchServer instance;
	private static ServerSocket serverSocket;
	private static boolean keeprunning;
	private static boolean running;

	public static void init() throws StartupException {
		if (instance == null) {
			Out.writeln(Out.Info, "Starting Patch Server");

			try {
				instance = new PatchServer(8020);
			} catch (IOException e) {
				instance = null;
				throw new StartupException("Exception while opening serversocket: " + e.getMessage());
			}
			
			keeprunning = true;
			instance.start();
			Out.writeln(Out.Info, "Patch Server started");
		}
	}

	public PatchServer(int port) throws IOException {
		serverSocket = new ServerSocket(port);
		serverSocket.setSoTimeout(100);
	}

	public void run() {
		Out.writeln(Out.Info, "Patch Server Listening");
		running = true;
		while (keeprunning) {
			try {
				Socket socket = serverSocket.accept();
				PatchServerConnection connection = new PatchServerConnection(socket);
				connection.setDaemon(true);
				connection.start();
			} catch (IOException e) {
				// Accept timeout or socket error; retry on next loop iteration
			}
		}
		synchronized (instance) {
			running = false;
			instance.notifyAll();
		}
	}

	public static void stopServer() {
		if (instance != null) {
			synchronized (instance) {
				Out.writeln(Out.Info, "Stopping Patch Server");
				keeprunning = false;
				while (running) {
					try {
						instance.wait(100);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				Out.writeln(Out.Info, "Patch Server stopped");
			}
		}
	}
	
}
