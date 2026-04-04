package server.tools;

public final class Timer extends Thread {

	private static long realtime;
	private static int ingametime;

	private static Timer instance;
	private static boolean keeprunning;
	private static boolean running;

	public static void init() {
		if (instance == null) {
			instance = new Timer();
			
			keeprunning = true;
			instance.setPriority(Thread.MAX_PRIORITY);
			instance.start();
		}
	}

	public static void stopServer() {
		if (instance != null) {
			keeprunning = false;
			synchronized (instance) {
				while (running) {
					try {
						instance.wait(100);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}
			instance = null;
			Out.writeln(Out.Info, "Timer stopped");
		}
	}

	public void run () {
		running = true;
		while (keeprunning) {
			realtime = System.currentTimeMillis();
			ingametime = (int) (realtime % (6*24*60*1000));

			try {
				sleep(1);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		synchronized (instance) {
			running = false;
			instance.notifyAll();
		}
	}

	public static int getIngametime() {
		return ingametime;
	}

	public static long getRealtime() {
		return realtime;
	}

}
