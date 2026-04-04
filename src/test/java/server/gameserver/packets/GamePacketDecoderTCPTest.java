package server.gameserver.packets;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the TCP packet decoder base class.
 */
public class GamePacketDecoderTCPTest {

    // Minimal concrete implementation for testing
    static class TestDecoder extends GamePacketDecoderTCP {
        public TestDecoder(byte[] data) {
            super(data);
        }
    }

    @Test
    public void testReadShort() {
        byte[] data = { 0x37, 0x02 };
        TestDecoder d = new TestDecoder(data);
        assertEquals(0x0237, d.readShort());
    }

    @Test
    public void testReadShortLittleEndian() {
        byte[] data = { (byte) 0x80, (byte) 0x84 };
        TestDecoder d = new TestDecoder(data);
        // 0x80 | (0x84 << 8) = 128 + 33792 = 33920 = 0x8480
        assertEquals(0x8480, d.readShort());
    }

    @Test
    public void testReadInt() {
        byte[] data = { 0x01, 0x02, 0x03, 0x04 };
        TestDecoder d = new TestDecoder(data);
        assertEquals(0x04030201, d.readInt());
    }

    @Test
    public void testReadCStringNormal() {
        // "test\0" = 5 bytes, len=5 means read 4 chars + skip null
        byte[] data = { 't', 'e', 's', 't', 0x00 };
        TestDecoder d = new TestDecoder(data);
        String result = d.readCString(5);
        assertEquals("test", result);
    }

    @Test
    public void testReadCStringLength1() {
        // Just a null terminator, len=1
        byte[] data = { 0x00 };
        TestDecoder d = new TestDecoder(data);
        String result = d.readCString(1);
        assertEquals("", result);
    }

    @Test
    public void testReadCStringLength0() {
        // Edge case: length 0 should not crash
        byte[] data = { 0x00 };
        TestDecoder d = new TestDecoder(data);
        String result = d.readCString(0);
        assertEquals("", result);
    }

    @Test
    public void testReadCStringNegativeLength() {
        // Edge case: negative length should not crash
        byte[] data = { 0x00 };
        TestDecoder d = new TestDecoder(data);
        String result = d.readCString(-5);
        assertEquals("", result);
    }

    @Test
    public void testReadEncryptedString() {
        // Password encoding: each char is (readShort() >> 4) - key
        // For key=0x42, char 'A'=0x41: short = (0x41 + 0x42) << 4 = 0x830
        int key = 0x42;
        int encodedA = (('A' + key) << 4); // 0x0830
        byte[] data = {
            (byte)(encodedA & 0xFF), (byte)((encodedA >> 8) & 0xFF)
        };
        TestDecoder d = new TestDecoder(data);
        String result = d.readEncryptedString(1, key);
        assertEquals("A", result);
    }

    @Test
    public void testReadEncryptedStringLength0() {
        byte[] data = {};
        TestDecoder d = new TestDecoder(data);
        String result = d.readEncryptedString(0, 0x42);
        assertEquals("", result);
    }

    @Test
    public void testSkipAndRead() {
        byte[] data = { 0x00, 0x00, 0x42 };
        TestDecoder d = new TestDecoder(data);
        d.skip(2);
        assertEquals(0x42, d.read());
    }
}
