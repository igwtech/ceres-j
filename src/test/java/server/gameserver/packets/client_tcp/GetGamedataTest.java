package server.gameserver.packets.client_tcp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.Test;

import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.packets.server_tcp.Gamedata;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Packet830D;
import server.gameserver.packets.server_tcp.UDPServerData;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.GameServerEvent;
import server.interfaces.ServerTCPPacket;
import server.tools.PriorityList;

/**
 * Functional test for {@link GetGamedata} — pins both
 * execution paths AND the carefully-documented packet-order
 * contract of the delayed {@code GetGamedataAnswer}.
 *
 * <p>Order is load-bearing: UDPServerData MUST arrive before
 * Location, otherwise the modern client triggers a world-change
 * before its NetMgr socket fields are populated and joins a
 * session at uninitialised memory — "Connection to worldserver
 * failed" 15s later. The Packet830D in the middle is the
 * GameinfoReady that retail sends between UDPServerData and
 * Location.
 */
public class GetGamedataTest {

    @Test
    public void executeOnTcpEmitsGamedataImmediate() {
        // The TCP-connection-direct execute path: sends just
        // Gamedata (the immediate ack before the delayed burst).
        CapturingTCPConnection cap = new CapturingTCPConnection();

        new GetGamedata(new byte[]{(byte) 0x87, 0x37, 0, 0, 0, 0})
                .execute(cap);

        List<ServerTCPPacket> sent = cap.received();
        assertEquals(1, sent.size());
        assertTrue(sent.get(0) instanceof Gamedata);
    }

    @Test
    public void executeOnPlayerSchedulesDeferredEvent() throws Exception {
        // The Player-direct execute path: schedules a
        // GetGamedataAnswer for ~200ms later. Verify by
        // peeking at the player's event queue.
        Player pl = PacketTestFixture.newPlayer();
        Field f = Player.class.getDeclaredField("eventList");
        f.setAccessible(true);
        PriorityList queue = (PriorityList) f.get(pl);
        assertTrue("event queue must start empty",
                queue.isEmpty());

        new GetGamedata(new byte[6]).execute(pl);

        assertFalse("must enqueue a GetGamedataAnswer",
                queue.isEmpty());
        GameServerEvent first = (GameServerEvent) queue.getFirst();
        assertEquals("GetGamedataAnswer",
                first.getClass().getSimpleName());
    }

    @Test
    public void deferredAnswerEmitsPacketsInRetailOrder()
            throws Exception {
        // Drive the Answer event by hand — we don't want to
        // wait 200ms in a unit test.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new GetGamedata(new byte[6]).execute(pl);

        Field f = Player.class.getDeclaredField("eventList");
        f.setAccessible(true);
        PriorityList queue = (PriorityList) f.get(pl);
        GameServerEvent answer = (GameServerEvent) queue.getFirst();
        answer.execute(pl);

        // The contract: Gamedata → UDPServerData → Packet830D
        //   → Location. Order is LOAD-BEARING (see GetGamedata
        //   javadoc on the "Connection to worldserver failed" bug).
        List<ServerTCPPacket> sent = cap.received();
        assertEquals("expected 4 TCP packets in retail order",
                4, sent.size());
        assertTrue("[0] must be Gamedata",
                sent.get(0) instanceof Gamedata);
        assertTrue("[1] must be UDPServerData (BEFORE Location)",
                sent.get(1) instanceof UDPServerData);
        assertTrue("[2] must be Packet830D (between UDPServerData "
                + "and Location)",
                sent.get(2) instanceof Packet830D);
        assertTrue("[3] must be Location (LAST — order critical)",
                sent.get(3) instanceof Location);
    }

    @Test
    public void udpServerDataPrecedesLocationRegressionGuard()
            throws Exception {
        // Explicit pin on the load-bearing ordering bit. Even if
        // future refactors change the surrounding packets, this
        // assertion must hold.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new GetGamedata(new byte[6]).execute(pl);
        Field f = Player.class.getDeclaredField("eventList");
        f.setAccessible(true);
        PriorityList queue = (PriorityList) f.get(pl);
        ((GameServerEvent) queue.getFirst()).execute(pl);

        List<ServerTCPPacket> sent = cap.received();
        int udpServerDataIdx = -1, locationIdx = -1;
        for (int i = 0; i < sent.size(); i++) {
            if (sent.get(i) instanceof UDPServerData)
                udpServerDataIdx = i;
            if (sent.get(i) instanceof Location)
                locationIdx = i;
        }
        assertTrue("UDPServerData must appear before Location "
                + "(see GetGamedata.java:38-44 — reversing this "
                + "causes 'Connection to worldserver failed' "
                + "15s after login)",
                udpServerDataIdx >= 0 && locationIdx >= 0
                        && udpServerDataIdx < locationIdx);
    }
}
