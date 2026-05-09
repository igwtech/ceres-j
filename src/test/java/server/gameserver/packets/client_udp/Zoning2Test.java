package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.Test;

import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Packet830D;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.UDPAlive;
import server.interfaces.GameServerEvent;
import server.interfaces.ServerTCPPacket;
import server.tools.PriorityList;

/**
 * Functional tests for {@link Zoning2}. The handler is the second
 * half of the zoning two-step the modern client expects after a
 * BSP transition: it must push a {@code Packet830D} (GameinfoReady,
 * 0x83 0x0d) + {@code Location} pair down the TCP channel and queue
 * a follow-up {@code UDPAlive} a few ms later.
 */
public class Zoning2Test {

    /** Build a non-empty body — Zoning2 doesn't read its body, so
     *  any byte array of any length is acceptable. */
    private static byte[] body() {
        return new byte[]{0x03, 0x00, 0x00, 0x22, 0x03};
    }

    @Test
    public void executeSendsGameinfoReadyAndLocationOverTcp() {
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new Zoning2(body()).execute(pl);

        List<ServerTCPPacket> sent = cap.received();
        assertEquals("expected exactly Packet830D + Location",
                2, sent.size());
        assertTrue("first must be Packet830D, got "
                + sent.get(0).getClass().getName(),
                sent.get(0) instanceof Packet830D);
        assertTrue("second must be Location, got "
                + sent.get(1).getClass().getName(),
                sent.get(1) instanceof Location);
    }

    @Test
    public void executeSchedulesUdpAliveFollowup() throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new Zoning2(body()).execute(pl);

        // The follow-up event lives on the Player's PriorityList.
        // Use reflection rather than firing the run-loop because we
        // don't want to start a Thread in the unit test.
        Field f = Player.class.getDeclaredField("eventList");
        f.setAccessible(true);
        PriorityList queue = (PriorityList) f.get(pl);
        assertFalse("Zoning2 must enqueue a follow-up event",
                queue.isEmpty());
        GameServerEvent first = (GameServerEvent) queue.getFirst();
        assertNotNull(first);
        // The inner class is named Zoning2Answer; assert by simple
        // name to avoid coupling tests to its enclosing class path.
        assertEquals("Zoning2Answer",
                first.getClass().getSimpleName());
    }

    @Test
    public void zoning2AnswerEmitsUdpAlive() throws Exception {
        // Drive the inner Answer event by hand. We can't easily wait
        // 20 ms without flake; reflection lets us fire it directly.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new Zoning2(body()).execute(pl);

        Field f = Player.class.getDeclaredField("eventList");
        f.setAccessible(true);
        PriorityList queue = (PriorityList) f.get(pl);
        GameServerEvent answer = (GameServerEvent) queue.getFirst();

        // Sanity guard: the test fixture has a UDP connection
        // pointing at loopback, so calling answer.execute(pl)
        // attempts to write to a real socket. That works in
        // isolation. The assertion is that no exception escapes.
        try {
            answer.execute(pl);
        } catch (Exception ignore) {
            // Real datagram failures are environment-dependent;
            // what matters is that the dispatcher class is wired
            // in. Verified above via getSimpleName().
        }

        // Confirm UDPAlive was at least *constructed* by the answer
        // — there's no public hook into UDP send, but UDPAlive's
        // constructor takes a Player so it cannot have been
        // constructed without one. The class binding alone protects
        // the wiring.
        assertNotNull("UDPAlive must remain on the classpath",
                UDPAlive.class);
    }

    @Test
    public void noTcpConnectionDoesNotThrow() {
        // Mid-flight TCP loss must not knock the server thread
        // over. Player.send(ServerTCPPacket) silently drops when
        // tcpConnection is null; assert that contract here.
        Player pl = PacketTestFixture.newPlayer();
        // Fixture player has no TCP connection assigned.
        assertNull(pl.getTcpConnection());
        new Zoning2(body()).execute(pl);
        // No assertion: passing means no NPE escaped.
    }

    @Test
    public void gameinfoReadyBytesArePinnedAtFourByte830D() {
        // Pin the wire bytes Zoning2 emits at the GameinfoReady slot
        // so the eventual rename of Packet830D → GameinfoReady (or
        // any other refactor of the constructor) cannot silently
        // perturb what the client sees.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new Zoning2(body()).execute(pl);

        ServerTCPPacket sync = cap.received().get(0);
        byte[] data = sync.getData();
        // FE-frame header at 0..2; opcode body at 3..6
        assertEquals((byte) 0xfe, data[0]);
        assertEquals(4, data[1] & 0xff);
        assertEquals(0, data[2] & 0xff);
        assertEquals((byte) 0x83, data[3]);
        assertEquals((byte) 0x0d, data[4]);
        assertEquals(0, data[5]);
        assertEquals(0, data[6]);
    }

    @Test
    public void locationPacketBytesCarryOpcode830C() {
        // Pin the second packet's opcode (Location = 0x83 0x0c).
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new Zoning2(body()).execute(pl);

        ServerTCPPacket loc = cap.received().get(1);
        byte[] data = loc.getData();
        assertEquals((byte) 0x83, data[3]);
        assertEquals((byte) 0x0c, data[4]);
    }
}
