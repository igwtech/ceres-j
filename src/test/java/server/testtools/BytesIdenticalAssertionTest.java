package server.testtools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

import server.gameserver.packets.server_tcp.Packet830D;

/**
 * Behavioural pin for {@link BytesIdenticalAssertion}. Loads the real
 * {@code docs/protocol/_data/packets.json} catalog and exercises every
 * public method, plus the catalog-key normalization helper.
 */
public class BytesIdenticalAssertionTest {

    @Test
    public void assertMatchesRetailPassesForKnownGameinfoReady() {
        // The one-liner this utility is designed to enable: the
        // catalog stores TCP samples body-only, but the assertion
        // strips the `fe LL LL` envelope automatically, so callers
        // pass full wire bytes from sliceWire().
        BytesIdenticalAssertion.assertMatchesRetail(
                BytesIdenticalAssertion.sliceWire(new Packet830D()),
                "tcp_s2c_830d");
    }

    @Test
    public void assertMatchesRetailFailsWithHexDiff() {
        byte[] wrong = { (byte) 0x83, 0x0d, 0x42, 0x00 };
        try {
            BytesIdenticalAssertion.assertMatchesRetail(wrong, "tcp_s2c_830d");
            fail("expected AssertionError");
        } catch (AssertionError e) {
            String msg = e.getMessage();
            assertTrue("message should mention catalog key, was: " + msg,
                    msg.contains("tcp_s2c_830d"));
            assertTrue("message should include first-diff offset, was: " + msg,
                    msg.contains("first differs at offset"));
            assertTrue("message should print expected hex, was: " + msg,
                    msg.contains("expected"));
            assertTrue("message should print actual hex, was: " + msg,
                    msg.contains("actual"));
        }
    }

    @Test
    public void loadRetailSamplesParsesKnownKey() {
        List<byte[]> samples =
                BytesIdenticalAssertion.loadRetailSamples("tcp_s2c_830d");
        assertFalse("expected at least one retail sample", samples.isEmpty());
        byte[] expectedBody = { (byte) 0x83, 0x0d, 0x00, 0x00 };
        boolean found = false;
        for (byte[] s : samples) {
            if (java.util.Arrays.equals(s, expectedBody)) {
                found = true;
                break;
            }
        }
        assertTrue("expected `83 0d 00 00` to appear in samples", found);
    }

    @Test
    public void loadRetailSamplesReturnsEmptyForUnknownKey() {
        List<byte[]> samples = BytesIdenticalAssertion.loadRetailSamples(
                "tcp_s2c_dead");
        assertTrue("unknown key must return empty list, got "
                + samples.size(), samples.isEmpty());
    }

    @Test
    public void sliceWireTrimsPacket830DToSevenBytes() {
        byte[] wire = BytesIdenticalAssertion.sliceWire(new Packet830D());
        assertEquals("expected wire-meaningful prefix to be 7 bytes",
                7, wire.length);
        // FE-frame envelope is now finalized: fe 04 00 83 0d 00 00.
        assertEquals((byte) 0xfe, wire[0]);
        assertEquals(0x04, wire[1] & 0xff);
        assertEquals(0x00, wire[2] & 0xff);
        assertEquals((byte) 0x83, wire[3]);
        assertEquals((byte) 0x0d, wire[4]);
    }

    @Test
    public void describeDiffReportsLengthMismatch() {
        byte[] expected = { 0x01, 0x02, 0x03, 0x04 };
        byte[] actual = { 0x01, 0x02 };
        String diff = BytesIdenticalAssertion.describeDiff(expected, actual);
        assertTrue("diff should mention shared prefix, was: " + diff,
                diff.contains("share 2-byte prefix")
                        || diff.contains("lengths differ"));
        assertTrue("diff should print expected hex, was: " + diff,
                diff.contains("01 02 03 04"));
        assertTrue("diff should print actual hex, was: " + diff,
                diff.contains("01 02"));
    }

    @Test
    public void describeDiffPointsAtFirstDifferingOffset() {
        byte[] expected = { 0x10, 0x20, 0x30, 0x40 };
        byte[] actual = { 0x10, 0x20, (byte) 0x99, 0x40 };
        String diff = BytesIdenticalAssertion.describeDiff(expected, actual);
        assertTrue("diff should pinpoint offset 2, was: " + diff,
                diff.contains("first differs at offset 2"));
        assertTrue("diff should print expected byte 30, was: " + diff,
                diff.contains("expected 30"));
        assertTrue("diff should print actual byte 99, was: " + diff,
                diff.contains("actual 99"));
    }

    @Test
    public void toJsonKeyHandlesAllShapes() {
        assertEquals("TCP S->C 0x830d",
                BytesIdenticalAssertion.toJsonKey("tcp_s2c_830d"));
        assertEquals("TCP C->S 0x8000",
                BytesIdenticalAssertion.toJsonKey("tcp_c2s_8000"));
        assertEquals("UDP S->C 0x03/0x2c",
                BytesIdenticalAssertion.toJsonKey("udp_s2c_03_2c"));
        assertEquals("UDP S->C 0x03/0x07/0x01",
                BytesIdenticalAssertion.toJsonKey("udp_s2c_03_07_01"));
        assertEquals("UDP C->S 0x01",
                BytesIdenticalAssertion.toJsonKey("udp_c2s_01"));
    }

    @Test
    public void assertMatchesRetailSampleByIndexWorks() {
        List<byte[]> samples =
                BytesIdenticalAssertion.loadRetailSamples("tcp_s2c_830d");
        assertFalse(samples.isEmpty());
        // First catalogged sample for 830d is `83 0d 00 00`.
        BytesIdenticalAssertion.assertMatchesRetailSample(
                samples.get(0), "tcp_s2c_830d", 0);
    }

    @Test
    public void assertMatchesRetailFailsCleanlyForUnknownKey() {
        try {
            BytesIdenticalAssertion.assertMatchesRetail(
                    new byte[] { 0x00 }, "tcp_s2c_dead");
            fail("expected AssertionError for unknown key");
        } catch (AssertionError e) {
            assertTrue("message should mention 'no retail samples', was: "
                    + e.getMessage(),
                    e.getMessage().contains("no retail samples"));
        }
    }
}
