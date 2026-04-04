package server.tools;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import server.exceptions.StartupException;

public final class Out {

	private static SimpleDateFormat sdf;
	private static FileWriter fw;
	private static Object lock = new Object();
	private static long lastTime;
	private static String lastTimeString;
	public static FileWriter fw_debug;
	private final static String[] typeText = {
		"Info", "Error", "Warning"
	};

	public final static int Info = 0;
	public final static int Error = 1;
	public final static int Warning = 2;

	/**
	 * initialize the consoleoutput and logfileoutput
	 * @throws StartupException 
	 */
	public static void init() throws StartupException {
		sdf = new SimpleDateFormat("dd.MM.yy HH:mm:ss:SSS z");
		lastTime = System.currentTimeMillis();
		lastTimeString = sdf.format(new Date(lastTime)) + ' ';
		try {
			fw = new FileWriter("log" + File.separatorChar + "ceres.log", true);
		} catch (IOException e) {
			e.printStackTrace();
			throw new StartupException("Failed opening log file.");
		}
		writeln(Info, "Logging started");
	}

	public static void stopServer() {
		writeln(Info, "Logging stopped");
		try {
			fw.close();
		} catch (IOException e) {
		}
	}

	/**
	 * @param string Text to be shown and logged 
	 */
	public static void writeln(int messageType, String s) {
		if (sdf == null) {
			// Not yet initialized — print to stdout only
			System.out.println("[" + typeText[messageType] + "] " + s);
			return;
		}
		String complete = format(typeText[messageType], s);
		synchronized (lock) {
			System.out.print(complete);
			try {
				fw.write(complete);
			} catch (IOException e) {
			}
		}
	}

	private static String format(String messageTypeString, String s) {
		if (lastTime < Timer.getRealtime()) {
			lastTime = Timer.getRealtime();
			lastTimeString = sdf.format(new Date(lastTime)) + ' ';
		}
		if (messageTypeString.length() < 7) {
			return lastTimeString + messageTypeString + ("       ".substring(messageTypeString.length())) + ": " + s + '\n';
		} else {
			return lastTimeString + messageTypeString + ": " + s + '\n';
		}
	}

	public static void debug(String messageTypeString, String s) {
		String complete = format(messageTypeString, s);
		synchronized (lock) {
			System.out.print(complete);
			try {
				fw_debug.write(complete);
			} catch (IOException e) {
			}
		}
	}	
}
