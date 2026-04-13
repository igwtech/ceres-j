package server.networktools;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Random;

import org.junit.Test;

/**
 * Round-trip tests for the NC2 UDP wire cipher (LFSR + cipher-feedback).
 *
 * <p>The retail client uses the SAME cipher for both directions, so a
 * single encrypt/decrypt pair must round-trip arbitrary plaintext. The
 * algorithm is validated against retail captures by
 * {@code tools/decrypt-retail.py}; these tests guard the Java port.
 */
public class WireEncryptTest {

    @Test
    public void roundTripsMinimalHandshake() {
        byte[] plain = new byte[] {
                0x01, 0x3e, (byte) 0xf4, (byte) 0x8e, 0x52,
                (byte) 0x8e, 0x52, (byte) 0x9a, 0x3e, 0x00
        };
        byte[] wire = WireEncrypt.encrypt(plain);

        // 4-byte wire header prepended (seed + length).
        assertEquals(plain.length + 4, wire.length);
        // Plaintext must NOT be visible at the start of the wire bytes.
        assertNotEquals(plain[0], wire[4]);

        byte[] decoded = WireEncrypt.decrypt(wire);
        assertArrayEquals(plain, decoded);
    }

    @Test
    public void roundTripsGamedataPacket() {
        // Looks like a real 0x13 gamedata frame: outer header + counters
        // + 2-byte LE size + reliable wrapper + sub-type.
        byte[] plain = new byte[] {
                0x13, 0x05, 0x00, 0x06, 0x00,   // header + outer counters
                0x07, 0x00,                      // 2B LE inner size
                0x03,                            // reliable wrapper
                0x01, 0x00,                      // seq counter
                0x08,                            // ZoningEnd sub-type
                0x07, 0x00,                      // mapId
                0x00                             // status
        };
        byte[] wire = WireEncrypt.encrypt(plain);
        byte[] decoded = WireEncrypt.decrypt(wire);
        assertArrayEquals(plain, decoded);
    }

    @Test
    public void roundTripsLargePacket() {
        byte[] plain = new byte[1024];
        new Random(0xC0FFEE).nextBytes(plain);
        plain[0] = 0x13; // realistic outer header

        byte[] wire = WireEncrypt.encrypt(plain);
        assertEquals(plain.length + 4, wire.length);

        byte[] decoded = WireEncrypt.decrypt(wire);
        assertArrayEquals(plain, decoded);
    }

    @Test
    public void lengthFieldMatchesPlaintextSize() {
        byte[] plain = new byte[] { 0x03, 0x2a, 0x01, 0x00 };
        byte[] wire = WireEncrypt.encrypt(plain);

        // Decrypt the 2-byte length field using the LFSR state derived
        // from the seed (bytes 0-1 in the clear). The recovered length
        // must equal the plaintext size.
        int seed = (wire[0] & 0xFF) | ((wire[1] & 0xFF) << 8);
        int[] state = { seed };
        int key1 = WireEncrypt.lfsrByte(state, wire[1] & 0xFF);
        int lenLo = (key1 ^ (wire[2] & 0xFF)) & 0xFF;
        int key2 = WireEncrypt.lfsrByte(state, wire[2] & 0xFF);
        int lenHi = (key2 ^ (wire[3] & 0xFF)) & 0xFF;
        assertEquals(plain.length, (lenHi << 8) | lenLo);
    }

    @Test
    public void decryptRejectsTooShortWire() {
        assertNull(WireEncrypt.decrypt(new byte[0]));
        assertNull(WireEncrypt.decrypt(new byte[3]));
    }

    @Test
    public void decryptRejectsInconsistentLength() {
        // Build a valid wire packet, then corrupt the encrypted-length
        // bytes so the decoded length would exceed what's available.
        byte[] plain = new byte[] { 0x13, 0x01, 0x02, 0x03 };
        byte[] wire = WireEncrypt.encrypt(plain);
        wire[2] = (byte) 0xFF;
        wire[3] = (byte) 0xFF;

        // Must NOT throw; must return null for malformed input.
        assertNull(WireEncrypt.decrypt(wire));
    }

    @Test
    public void randomisedRoundTrip() {
        Random r = new Random(1);
        for (int len = 1; len < 256; len++) {
            byte[] plain = new byte[len];
            r.nextBytes(plain);
            byte[] wire = WireEncrypt.encrypt(plain);
            byte[] decoded = WireEncrypt.decrypt(wire);
            assertNotNull("len=" + len, decoded);
            assertArrayEquals("len=" + len, plain, decoded);
        }
    }
}
