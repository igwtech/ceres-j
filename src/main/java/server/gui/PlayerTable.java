package server.gui;

import javax.swing.table.AbstractTableModel;
import server.gameserver.PlayerManager;
import server.gameserver.Player;

public class PlayerTable extends AbstractTableModel {

	private static final long serialVersionUID = 2901002310738308999L;

	public int getRowCount() {
		return PlayerManager.getPlayers().size();
	}

	public int getColumnCount() {
		return 3;
	}

	public Object getValueAt(int rowIndex, int columnIndex) {
		Player pl = PlayerManager.getPlayers().get(rowIndex);
		switch (columnIndex) {
		case 0 :
			return pl.getAccount().getUsername();
		case 1 :
			return pl.getCharacter().getName();
		case 2 :
			return pl.getZone().getWorldname();
		}
		return null;
	}
}
