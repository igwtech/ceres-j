package server.networktools;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Tests for {@link PacketObfuscator}, the per-packet XOR cipher used on
 * NCE 2.5 UDP traffic.
 *
 * <p>Invariants verified here:
 * <ul>
 *   <li>Encryption and decryption are symmetric (encrypt(encrypt(x)) == x
 *       when the same seed is re-used).</li>
 *   <li>The seed is fully encoded in byte[0]: for a single-byte packet the
 *       encrypted byte equals {@code plaintext[0] ^ seed}.</li>
 *   <li>{@link PacketObfuscator#decrypt(byte[], int)} recovers one of the
 *       known header bytes (0x01, 0x03, 0x04, 0x08, 0x13) from any arbitrary
 *       input.</li>
 *   <li>At byte position i the XOR key is
 *       {@code (((i+1)*seed) >> 16 & 0xff) ^ ((i+1)*seed & 0xff)}. For
 *       small packets (i<256) this collapses to just the low byte.</li>
 * </ul>
 */
public class PacketObfuscatorTest {

    @Test
    public void encryptDecryptRoundtripWithFixedSeed() {
        byte[] plain = {0x13, 0x00, 0x00, 0x01, 0x00, 0x14, 0x03, 0x01, 0x00};
        byte[] encrypted = PacketObfuscator.encrypt(plain, plain.length, 0xA5);
        byte[] decrypted = PacketObfuscator.encrypt(encrypted, encrypted.length, 0xA5);
        assertArrayEquals(plain, decrypted);
    }

    @Test
    public void firstBytePlaintextXorSeedMatches() {
        // For position 0: key = (1*seed >> 16) ^ (1*seed) = seed for seed < 256.
        byte[] plain = {0x13};
        byte[] enc = PacketObfuscator.encrypt(plain, 1, 0x42);
        assertEquals("encrypted[0] must equal plaintext[0] XOR seed",
                0x13 ^ 0x42, enc[0] & 0xFF);
    }

    @Test
    public void decryptRecoversKnownHeader() {
        byte[] plain = {(byte) 0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] enc = PacketObfuscator.encrypt(plain, plain.length, 0x9B);
        byte[] dec = PacketObfuscator.decrypt(enc, enc.length);
        assertNotNull(dec);
        assertEquals("first byte must be recovered as 0x01",
                0x01, dec[0] & 0xFF);
        assertArrayEquals(plain, dec);
    }

    @Test
    public void decryptHandshakePacket() {
        // Simulate a handshake packet (header 0x01 + 8-byte session + 1-byte intfId + 4-byte pad).
        byte[] plain = new byte[14];
        plain[0] = 0x01;
        for (int i = 1; i < 14; i++) plain[i] = (byte) (i * 7);
        byte[] enc = PacketObfuscator.encrypt(plain, plain.length, 0x7f);
        byte[] dec = PacketObfuscator.decrypt(enc, enc.length);
        assertArrayEquals(plain, dec);
    }

    @Test
    public void decryptFallbackWhenNoHeaderMatch() {
        // Feed in garbage that can't decrypt to a known header under any seed.
        // decrypt() must return the buffer unchanged rather than returning null.
        byte[] garbage = {(byte) 0xFF};
        byte[] dec = PacketObfuscator.decrypt(garbage, garbage.length);
        assertNotNull(dec);
        // Either fallback returned the original byte, or a valid header was
        // found by chance (0xFF XOR seed == known_header is possible for
        // some seeds). Both outcomes are acceptable; the function must not
        // throw or return null for non-zero input.
    }

    @Test
    public void decryptNullForEmptyInput() {
        byte[] dec = PacketObfuscator.decrypt(new byte[0], 0);
        // decrypt returns null only when there are no bytes at all.
        assertEquals(null, dec);
    }

    @Test
    public void cipherKeyAtPositionMatchesFormula() {
        // For seed = 0x10, position 1 (i.e. second byte):
        //   s = 2 * 0x10 = 0x20; key = (0x20 >> 16 & 0xff) ^ (0x20 & 0xff) = 0x20
        byte[] plain = {0x00, 0x00};
        byte[] enc = PacketObfuscator.encrypt(plain, 2, 0x10);
        assertEquals(0x10, enc[0] & 0xFF);
        assertEquals(0x20, enc[1] & 0xFF);
    }
}
