package server.database.accounts;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;

import com.csvreader.CsvReader;
import com.csvreader.CsvWriter;

import server.exceptions.StartupException;
import server.tools.Config;
import server.tools.Out;

public class AccountManager {

	private static LinkedList<Account> accountList;
	private static int accountCounter;

	public static void init() throws StartupException {
		load();
		findaccountCounter();
	}
	
	public static void stopServer() {
		save();
	}
	
	private static void load() {
		accountList = new LinkedList<Account>();
		try {
			CsvReader reader = new CsvReader("database/accounts.csv");
			reader.readHeaders();
			
			while (reader.readRecord()) {
				Account ua = new Account(Integer.parseInt(reader.get("id")));
				ua.setUsername(reader.get("username"));
				ua.setPassword(reader.get("password"));
				ua.setChar(0, Integer.parseInt(reader.get("char1")));
				ua.setChar(1, Integer.parseInt(reader.get("char2")));
				ua.setChar(2, Integer.parseInt(reader.get("char3")));
				ua.setChar(3, Integer.parseInt(reader.get("char4")));
				ua.setStatus(reader.get("status"));
				accountList.add(ua);
			}

			reader.close();
		} catch (IOException e) {
			// Failed to load accounts CSV; starting with empty list
		}
		Out.writeln(Out.Info, "Loaded " + accountList.size() + " Accounts");
	}

	private static void save() {
		try {
			CsvWriter writer = new CsvWriter("database/accounts.csv");
			writer.write("id");
			writer.write("username");
			writer.write("password");
			writer.write("char1");
			writer.write("char2");
			writer.write("char3");
			writer.write("char4");
			writer.write("status");
			writer.endRecord();
			
			for ( Iterator<Account> i = accountList.iterator(); i.hasNext(); )
			{
				Account ua = i.next();
				writer.write(String.valueOf(ua.getId()));
				writer.write(ua.getUsername());
				writer.write(ua.getPassword());
				writer.write(String.valueOf(ua.getChar(0)));
				writer.write(String.valueOf(ua.getChar(1)));
				writer.write(String.valueOf(ua.getChar(2)));
				writer.write(String.valueOf(ua.getChar(3)));
				writer.write(ua.getStatus());
				writer.endRecord();		  
			}
			
			writer.close();
		} catch (IOException e) {
			// Failed to save accounts CSV
		}
	}

	private static void findaccountCounter() {
		int id = 0;
		for ( Iterator<Account> i = accountList.iterator(); i.hasNext(); )
		{
			Account ua = i.next();
			if (ua.getId() > id) {
				id = ua.getId();
			}
		}
		accountCounter = id + 1;
	}

	public static Account getAccount(String username, String password) {
		Account ua = null;
		synchronized (accountList) {
			for (Iterator<Account> i = accountList.iterator(); i.hasNext();) {
				Account tua = i.next();
				if (tua.getUsername().equalsIgnoreCase(username)) {
					ua = tua;
					break;
				}
			}
			if ((ua == null) && (Config.getProperty("AutoCreateAccounts").equals("true"))) {
				ua = createAccount(username, password);
				accountList.add(ua);
			}
		}
		if (ua == null)	return null;
		if (!ua.ckeckPassword(password)) return null;
		if (ua.isStatus(Account.STATUS_BANNED)) return null;
		return ua;
	}

	public static LinkedList<Account> getAccounts() {
		synchronized (accountList) {
			return new LinkedList<>(accountList);
		}
	}

	private static Account createAccount(String username, String password) {
		Account ua = new Account(accountCounter);
		ua.setUsername(username);
		ua.setPassword(password);
		accountCounter++;
		return ua;
	}
}
