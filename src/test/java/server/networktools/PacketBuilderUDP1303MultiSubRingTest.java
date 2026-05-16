package server.networktools;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Regression test for the reliable-livelock that blocked the
 * plaza_p1 → plaza_p3 zone-cross (task #172).
 *
 * <p>{@link PacketBuilderUDP1303#newSubPacket()} calls
 * {@code incandgetSessionCounter()} for <em>every</em> sub-packet,
 * so a multi-sub reliable burst (e.g. the post-cross init burst:
 * charinfo + worldinfo + the dest-zone NPC roster, all in one
 * 1303) consumes seqs {@code N, N+1, N+2, …}. The old
 * {@code getDatagramPackets()} recorded only the first seq
 * ({@code recordedSeq}) into the {@link ReliablePacketRing}, and
 * stored the whole concatenated datagram tail as its body. Every
 * 2nd+ sub-packet was therefore a phantom seq the client saw on
 * the wire but the ring could never retransmit — a
 * {@code C→S 0x01 [seq]} retransmit-request for it was answered
 * with nothing, so the client NAK-flooded forever and the
 * "Synchronizing" overlay never cleared.
 *
 * <p>Pcap ground truth ({@code /tmp/ceres_p1p3.pcap}, 2026-05-16):
 * client requested seqs 1..50, server could only replay a
 * scattered subset (missing 4,5,6,7,8,9,13,14,18,20,22,31 — the
 * 2nd+ sub-packets of multi-sub bursts) → permanent livelock.
 */
public class PacketBuilderUDP1303MultiSubRingTest {

    /**
     * Three sub-packets in one 1303 → three ring entries, each
     * keyed by its own seq and holding only its own inner body
     * (post {@code [0x03][seq LE2]}).
     */
    @Test
    public void everySubPacketRecordedWithItsOwnSeqAndBody() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        ReliablePacketRing ring =
                pl.getUdpConnection().reliableRing();
        assertEquals(0, ring.size());

        PacketBuilderUDP1303 b = new PacketBuilderUDP1303(pl);
        // sub-packet 0 (seq 1): body = AA BB
        b.write(0xAA);
        b.write(0xBB);
        // sub-packet 1 (seq 2): body = CC
        b.newSubPacket();
        b.write(0xCC);
        // sub-packet 2 (seq 3): body = DD EE FF
        b.newSubPacket();
        b.write(0xDD);
        b.write(0xEE);
        b.write(0xFF);

        b.getDatagramPackets();

        assertEquals("all 3 sub-packets recorded", 3, ring.size());
        assertArrayEquals("seq 1 body = its own bytes only",
                new byte[]{(byte) 0xAA, (byte) 0xBB}, ring.get(1));
        assertArrayEquals("seq 2 body = its own bytes only",
                new byte[]{(byte) 0xCC}, ring.get(2));
        assertArrayEquals("seq 3 body = its own bytes only",
                new byte[]{(byte) 0xDD, (byte) 0xEE, (byte) 0xFF},
                ring.get(3));
    }

    /**
     * The 2nd+ sub-packet seqs must be individually retrievable —
     * the exact failure mode the pcap showed (client NAKs seq 2,
     * ring had only seq 1).
     */
    @Test
    public void secondSubPacketSeqIsNotPhantom() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        ReliablePacketRing ring =
                pl.getUdpConnection().reliableRing();

        PacketBuilderUDP1303 b = new PacketBuilderUDP1303(pl);
        b.write(0x2C); // charinfo-ish sub-op for seq 1
        b.newSubPacket();
        b.write(0x28); // worldinfo-ish sub-op for seq 2
        b.getDatagramPackets();

        assertNotNull("seq 2 must be retransmittable, not phantom",
                ring.get(2));
        assertArrayEquals(new byte[]{0x28}, ring.get(2));
    }

    /**
     * Single sub-packet still behaves exactly as before (no
     * regression of the {@link PacketBuilderUDP1303RingHookTest}
     * contract).
     */
    @Test
    public void singleSubPacketUnchanged() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        ReliablePacketRing ring =
                pl.getUdpConnection().reliableRing();

        PacketBuilderUDP1303 b = new PacketBuilderUDP1303(pl);
        b.write(0x33);
        b.write(0xFF);
        b.write(0x00);
        b.getDatagramPackets();

        assertEquals(1, ring.size());
        assertArrayEquals(new byte[]{0x33, (byte) 0xFF, 0x00},
                ring.get(1));
    }
}
