package server.gameserver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import server.tools.Out;

final class ListenerTCP extends Thread {

	public boolean running;
	private ServerSocket serverSocket;

	public ListenerTCP(int port) throws IOException {
		serverSocket = new ServerSocket(port);
		serverSocket.setSoTimeout(100);
	}

	public void run() {
		Out.writeln(Out.Info, "Game Server Listening (TCP)");
		running = true;
		while (GameServer.keeprunning) {
			try {
				Socket socket = serverSocket.accept();
				new GameServerTCPConnection(socket).start();
			} catch (IOException e) {
				// Accept timeout or socket error; retry on next loop iteration
			}
		}
		synchronized (this) {
			running = false;
			notifyAll();
		}
	}

}
