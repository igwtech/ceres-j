package server.networktools;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for {@link ReliablePacketRing} — the bounded
 * sequence-keyed buffer of recently-emitted reliable sub-packets
 * used by the C→S 0x01 ReliableAckRequest retransmit responder
 * (task #136 / #151).
 */
public class ReliablePacketRingTest {

    @Test
    public void recordAndGet_returnsStoredPayload() {
        ReliablePacketRing ring = new ReliablePacketRing(4);
        byte[] body = new byte[]{0x03, 0x05, 0x00, 0x33,
                (byte) 0xff, 0x00};

        ring.record(5, body);

        byte[] got = ring.get(5);
        assertArrayEquals("get must return the stored payload",
                body, got);
        assertEquals(1, ring.size());
        assertTrue(ring.contains(5));
    }

    @Test
    public void getReturnsDefensiveCopy() {
        ReliablePacketRing ring = new ReliablePacketRing();
        byte[] body = new byte[]{0x01, 0x02, 0x03};
        ring.record(1, body);

        byte[] first = ring.get(1);
        first[0] = (byte) 0xFF;  // mutate caller's copy

        byte[] second = ring.get(1);
        assertEquals("ring's stored payload must NOT be mutated by "
                + "caller mutation of returned copy",
                0x01, second[0]);
    }

    @Test
    public void recordDefensiveCopiesInputPayload() {
        ReliablePacketRing ring = new ReliablePacketRing();
        byte[] body = new byte[]{0x01, 0x02, 0x03};
        ring.record(7, body);
        body[0] = (byte) 0xFF;  // mutate caller's buffer

        byte[] got = ring.get(7);
        assertEquals("ring's stored payload must NOT be mutated by "
                + "caller mutation of input buffer",
                0x01, got[0]);
    }

    @Test
    public void getReturnsNullForUnknownSeq() {
        ReliablePacketRing ring = new ReliablePacketRing();
        ring.record(1, new byte[]{0x00});
        assertNull(ring.get(999));
        assertFalse(ring.contains(999));
    }

    @Test
    public void evictsOldestWhenAtCapacity() {
        ReliablePacketRing ring = new ReliablePacketRing(3);
        ring.record(1, new byte[]{1});
        ring.record(2, new byte[]{2});
        ring.record(3, new byte[]{3});
        assertEquals(3, ring.size());
        // 4th entry pushes seq=1 out
        ring.record(4, new byte[]{4});
        assertEquals(3, ring.size());
        assertNull("seq=1 must be evicted", ring.get(1));
        assertNotNull("seq=2 must still be retained", ring.get(2));
        assertNotNull("seq=4 must be retained", ring.get(4));
    }

    @Test
    public void evictsMultipleOldestWhenManyAdded() {
        ReliablePacketRing ring = new ReliablePacketRing(2);
        for (int i = 1; i <= 100; i++) {
            ring.record(i, new byte[]{(byte) i});
        }
        // Only the last 2 (99, 100) should remain
        assertEquals(2, ring.size());
        assertNull(ring.get(98));
        assertNotNull(ring.get(99));
        assertNotNull(ring.get(100));
    }

    @Test
    public void rerecordingSameSeqRefreshesEvictionOrder() {
        ReliablePacketRing ring = new ReliablePacketRing(3);
        ring.record(1, new byte[]{1});
        ring.record(2, new byte[]{2});
        ring.record(3, new byte[]{3});
        // Re-record seq=1 → it moves to the END of eviction order
        ring.record(1, new byte[]{0x10});
        // Now adding seq=4 should evict seq=2 (the new oldest), not seq=1
        ring.record(4, new byte[]{4});

        assertNotNull("re-recorded seq=1 must still be retained",
                ring.get(1));
        assertEquals("re-recorded payload must be the NEW value",
                0x10, ring.get(1)[0]);
        assertNull("seq=2 must be evicted (new oldest)",
                ring.get(2));
    }

    @Test
    public void seqWraparoundIsTreatedAsLE16() {
        // The seq counter is LE16 on the wire — values past 0xFFFF
        // wrap. Verify the ring masks correctly.
        ReliablePacketRing ring = new ReliablePacketRing();
        ring.record(0x10005, new byte[]{1});  // = 5 after & 0xFFFF
        assertNotNull("seq with high bits set must store as LE16",
                ring.get(0x10005));
        assertNotNull("equivalent low-16-bit seq retrieves same",
                ring.get(5));
        assertEquals(1, ring.size());
    }

    @Test
    public void clearRemovesAllEntries() {
        ReliablePacketRing ring = new ReliablePacketRing(10);
        for (int i = 0; i < 5; i++) ring.record(i, new byte[]{1});
        assertEquals(5, ring.size());
        ring.clear();
        assertEquals(0, ring.size());
        assertNull(ring.get(0));
    }

    @Test
    public void recordRejectsNullPayload() {
        ReliablePacketRing ring = new ReliablePacketRing();
        try {
            ring.record(1, null);
            fail("expected IllegalArgumentException for null payload");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void capacityZeroRejected() {
        try {
            new ReliablePacketRing(0);
            fail("expected IllegalArgumentException for capacity=0");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        try {
            new ReliablePacketRing(-5);
            fail("expected IllegalArgumentException for negative capacity");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void defaultCapacityIs8192() {
        ReliablePacketRing ring = new ReliablePacketRing();
        assertEquals(ReliablePacketRing.DEFAULT_CAPACITY,
                ring.capacity());
        assertEquals(8192, ring.capacity());
    }

    @Test
    public void retailScenarioFiftyAcksWithinCapacity() {
        // Retail captures show the client may request retransmit of
        // up to ~50 seqs back. With default capacity 128, all 50
        // should be retrievable.
        ReliablePacketRing ring = new ReliablePacketRing();
        for (int seq = 1; seq <= 50; seq++) {
            ring.record(seq, new byte[]{(byte) seq});
        }
        for (int seq = 1; seq <= 50; seq++) {
            assertNotNull("seq " + seq + " must be retained",
                    ring.get(seq));
            assertEquals((byte) seq, ring.get(seq)[0]);
        }
    }
}
