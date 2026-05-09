package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.packets.server_tcp.InteractionAck;
import server.gameserver.packets.server_tcp.Packet838F;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.ServerTCPPacket;

/**
 * Functional tests for {@link UseItem} — pins the retail
 * interaction-commit ack contract. When the client sends
 * {@code 0x03/0x1f sub=0x17} (UseItem on a world object, e.g.
 * pressing E on a chair, medbed, vendor, or door), the server
 * MUST emit {@link Packet838F} on the TCP channel before any
 * dependent state-change packets. 1,392 retail samples back this
 * contract; the client uses 0x838f as its UI commit point.
 *
 * <p>Without 0x838f the client's input lock-out is not engaged
 * for the duration of the animation, leading to desync and
 * "interaction failed" UI states.
 */
public class UseItemTest {

    /** Build a 11-byte UseItem body matching the wire format
     *  {@code 0x03 [seq2] 0x1f [sub2] 0x17 [object_id LE32]}.
     *  UseItem.execute skips the leading 7 bytes and reads the
     *  4-byte object id. */
    private static byte[] buildBody(int objectId) {
        byte[] b = new byte[11];
        b[0]  = 0x03;          // outer reliable
        b[1]  = 0x42;          // seq lo
        b[2]  = 0x00;          // seq hi
        b[3]  = 0x1f;          // gamedata
        b[4]  = 0x05;          // sub lo
        b[5]  = 0x00;          // sub hi
        b[6]  = 0x17;          // sub-tag UseItem
        b[7]  = (byte) (objectId        & 0xff);
        b[8]  = (byte) ((objectId >> 8 ) & 0xff);
        b[9]  = (byte) ((objectId >> 16) & 0xff);
        b[10] = (byte) ((objectId >> 24) & 0xff);
        return b;
    }

    @Test
    public void executeEmitsPacket838FOverTcp() {
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new UseItem(buildBody(0xdeadbeef)).execute(pl);

        List<ServerTCPPacket> tcpSent = cap.received();
        assertFalse("UseItem must emit at least one TCP packet "
                + "(the retail interaction-commit ack)",
                tcpSent.isEmpty());
        boolean found838F = tcpSent.stream()
                .anyMatch(p -> p instanceof Packet838F);
        assertTrue("first TCP packet must be Packet838F (retail "
                + "requires the ack BEFORE any state-change). "
                + "Got: " + tcpSent.get(0).getClass().getName(),
                tcpSent.get(0) instanceof Packet838F);
        assertTrue(found838F);
    }

    @Test
    public void packet838FCarriesRetailExactBytes() {
        // Drive UseItem and pull the captured Packet838F to
        // verify its on-the-wire bytes match retail. Avoids
        // duplicating Packet838FTest's pin but proves the
        // emission path doesn't accidentally mutate the bytes.
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new UseItem(buildBody(0x12345678)).execute(pl);

        ServerTCPPacket ack = cap.received().get(0);
        byte[] data = ack.getData();
        assertEquals((byte) 0xfe, data[0]);
        assertEquals(7, data[1] & 0xff);
        assertEquals(0, data[2] & 0xff);
        assertEquals((byte) 0x83, data[3]);
        assertEquals((byte) 0x8f, data[4]);
        for (int i = 5; i < 10; i++) {
            assertEquals("trailing zero offset " + i, 0, data[i]);
        }
    }

    @Test
    public void noTcpConnectionDoesNotThrow() {
        // If TCP is dropped mid-flight, UseItem must drop the
        // ack gracefully (Player.send(ServerTCPPacket) is null-
        // guarded). The handler must still attempt the rest of
        // its work — confirms the ack isn't load-bearing.
        Player pl = PacketTestFixture.newPlayer();
        assertNull(pl.getTcpConnection());
        new UseItem(buildBody(1)).execute(pl);
        // Pass = no exception escaped.
    }

    @Test
    public void tcpAckTripletInRetailOrder() {
        // The retail contract is:
        //   1. 0x838f commit BEFORE any state-change packet
        //   2. State-change packets (OpenDoor on UDP, etc.)
        //   3. 0xa002 transaction-ack PAIR after the state-change
        // Test the TCP ordering only — UDP state-change packets
        // are on a different transport. Expected TCP triplet:
        //   [Packet838F, InteractionAck, InteractionAck]
        Player pl = PacketTestFixture.newPlayer();
        CapturingTCPConnection cap = new CapturingTCPConnection();
        pl.setTcpConnection(cap);

        new UseItem(buildBody(99)).execute(pl);

        List<ServerTCPPacket> tcpSent = cap.received();
        assertEquals("expected 3 TCP packets: 838f + 2× a002 ack",
                3, tcpSent.size());
        assertTrue("first must be Packet838F (pre-state commit)",
                tcpSent.get(0) instanceof Packet838F);
        assertTrue("second must be InteractionAck (post-state)",
                tcpSent.get(1) instanceof InteractionAck);
        assertTrue("third must be InteractionAck (retail emits "
                + "the ack PAIR)",
                tcpSent.get(2) instanceof InteractionAck);
    }
}
