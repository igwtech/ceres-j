package server.gui;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.Dimension;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;

import server.gameserver.PlayerManager;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.TestPack;
import server.gameserver.packets.server_udp.TestPack03;
import server.exceptions.*;
import server.tools.Config;

public class Gui extends JFrame{ // will be extended in the future
	
	private static final long serialVersionUID = 1L;
	
	final static PlayerTable pt = new PlayerTable();
	
	static Gui gui;
	
	Gui(){
		super(Config.getProperty("ServerName"));
		JPanel jp = new JPanel();
		jp.setLayout(null);
		jp.setPreferredSize(new Dimension(500, 600));
        setContentPane(jp);
        
        JToolBar toolBar = new JToolBar();
        jp.add(toolBar, BorderLayout.PAGE_START);
        toolBar.setFloatable(false);
        
        JButton EndButton = new JButton();
        EndButton.setText("Shutdown");
        EndButton.setToolTipText("Shutdown Server");
        toolBar.add(EndButton);
        
        final JCheckBox checkb = new JCheckBox("13-03-Packet");
        toolBar.add(checkb);
        
        toolBar.setBounds(0, 0, 500, 30);
        
        final JTextField packetTextField = new JTextField(15);
        packetTextField.setToolTipText("Packet");
        packetTextField.setPreferredSize(new Dimension(350, 25));
        packetTextField.setBounds(5, 40, 370, 25);
        jp.add(packetTextField);
        
        JButton SendButton = new JButton();
        SendButton.setText("Send Packet");
        SendButton.setToolTipText("Send Packet");
        SendButton.setBounds(380, 40, 115, 25);
        jp.add(SendButton);
        
        final JTable table = new JTable(pt);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(100);
        table.getColumnModel().getColumn(0).setHeaderValue("Account");
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(1).setHeaderValue("Char");
        table.getColumnModel().getColumn(2).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setHeaderValue("Location");
        
        JScrollPane sp = new JScrollPane(table);
        sp.setBounds(5, 70, 490, 480);
		jp.add(sp);
		
		JButton UpdatePlListButton = new JButton();
		UpdatePlListButton.setText("Update PlayerList");
		UpdatePlListButton.setToolTipText("Update PlayerList");
		UpdatePlListButton.setBounds(350, 555, 145, 25);
        jp.add(UpdatePlListButton);
        
        JButton SendPlayersinZone = new JButton();
        SendPlayersinZone.setText("Send Players in Zone");
        SendPlayersinZone.setToolTipText("Send Players in Zone");
        SendPlayersinZone.setBounds(200, 555, 145, 25);
        jp.add(SendPlayersinZone);
        
        JButton SendPlayersPosinZone = new JButton();
        SendPlayersPosinZone.setText("Send PlayersPos in Zone");
        SendPlayersPosinZone.setToolTipText("Send Players Positions in Zone");
        SendPlayersPosinZone.setBounds(50, 555, 145, 25);
        jp.add(SendPlayersPosinZone);
        
        
        SendButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if(table.getSelectedRow() != -1){
					Player pl = PlayerManager.getPlayers().get(table.getSelectedRow());
					if(pl!= null){
						if(checkb.isSelected())
							pl.send(new TestPack03(pl,packetTextField.getText()));
						else
							pl.send(new TestPack(pl,packetTextField.getText()));
					}
					pl.send(new server.gameserver.packets.server_udp.LocalChatMessage(pl,packetTextField.getText()));
				}
			}
        });
        
        UpdatePlListButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				pt.fireTableDataChanged();
			}
        });
        
        SendPlayersinZone.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if(table.getSelectedRow() != -1){
					Player pl = PlayerManager.getPlayers().get(table.getSelectedRow());
					if(pl!= null){
						pl.getZone().sendPlayersinZone(pl);
					}
				}
			}
        });
        
        SendPlayersinZone.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if(table.getSelectedRow() != -1){
					Player pl = PlayerManager.getPlayers().get(table.getSelectedRow());
					if(pl!= null){
						pl.getZone().sendPlayersPosinZone(pl);
					}
				}
			}
        });
        
        EndButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				System.exit(0);
			}
        });
		
		pack();
		setVisible(true);
	}
	
	public static void init() throws StartupException{
		if(Config.getProperty("GUI").equals("true"))
			gui = new Gui();
	}

}
