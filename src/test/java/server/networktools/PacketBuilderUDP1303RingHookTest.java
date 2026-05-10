package server.networktools;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.ChatList;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Integration test for the {@link PacketBuilderUDP1303} →
 * {@link ReliablePacketRing} record hook (task #136 / #151,
 * P1 overlay-clear blocker).
 *
 * <p>Pins that every reliable {@code 0x03} sub-packet is recorded
 * into the session's ring on finalize ({@code getDatagramPackets()}),
 * so the {@code C→S 0x01 [seq LE2]} retransmit responder can look
 * up the body and resend it without re-running the original event
 * handler.
 *
 * <p>Tests use a real reliable subclass ({@link ChatList} = 0x03/0x33,
 * 2-byte body) since direct instantiation of the abstract builder
 * isn't useful for the wire-level integration.
 */
public class PacketBuilderUDP1303RingHookTest {

    @Test
    public void singleEmitRecordsBodyKeyedBySeq() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        ReliablePacketRing ring =
                pl.getUdpConnection().reliableRing();
        assertEquals("ring is empty before any emit",
                0, ring.size());

        ChatList pkt = new ChatList(pl);
        // Force finalize via getDatagramPackets()
        DatagramPacket[] dps = pkt.getDatagramPackets();
        assertNotNull(dps);
        assertEquals(1, dps.length);

        // ChatList body (post [03][seq LE2]) = `33 ff 00` (3 bytes)
        // Seq is the session counter — first emit is 1.
        assertEquals("ring has 1 entry after a single emit",
                1, ring.size());
        byte[] stored = ring.get(1);
        assertNotNull("seq=1 must be retained",
                stored);
        assertArrayEquals("stored body = sub-op + payload "
                + "(post 0x13/0x03/seq wrapper)",
                new byte[]{0x33, (byte) 0xff, 0x00}, stored);
    }

    @Test
    public void multipleEmitsAdvanceSeq() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        ReliablePacketRing ring =
                pl.getUdpConnection().reliableRing();

        for (int i = 0; i < 5; i++) {
            new ChatList(pl).getDatagramPackets();
        }
        assertEquals("ring has 5 entries", 5, ring.size());
        for (int seq = 1; seq <= 5; seq++) {
            assertNotNull("seq " + seq + " retained",
                    ring.get(seq));
        }
    }

    @Test
    public void recordedBodyIsIdenticalAcrossInstances() {
        // Identical packet content across emits → identical body
        // recorded (just different seq).
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        ReliablePacketRing ring =
                pl.getUdpConnection().reliableRing();

        new ChatList(pl).getDatagramPackets();
        new ChatList(pl).getDatagramPackets();

        byte[] body1 = ring.get(1);
        byte[] body2 = ring.get(2);
        assertArrayEquals("ChatList body deterministic across emits",
                body1, body2);
    }

    @Test
    public void getDatagramPacketsCalledTwiceDoesNotDoubleRecord() {
        // PacketBuilderUDP13's isFinished flag guards against
        // double-finalize. Verify the ring isn't double-recorded.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        ReliablePacketRing ring =
                pl.getUdpConnection().reliableRing();

        ChatList pkt = new ChatList(pl);
        pkt.getDatagramPackets();
        // Re-record on second call — but since isFinished is set
        // by super.getDatagramPackets(), the bytes don't change.
        // The ring's record() is overwrite-safe (refresh order).
        pkt.getDatagramPackets();

        assertEquals("ring has 1 entry, not 2", 1, ring.size());
    }
}
