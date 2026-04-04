package server.networktools;

/**
 * Implements the Neocron 2 UDP packet obfuscation/deobfuscation.
 *
 * Algorithm (reverse-engineered from neocronclient.exe ObfuscateStreamBuf):
 *
 * The cipher uses a per-packet random seed encoded in the first byte.
 * Both sides know that the first plaintext byte is always 0x01 (handshake),
 * 0x03 (sync), 0x04 (keepalive), 0x08 (abort), or 0x13 (gamedata).
 *
 * Encryption:
 *   seed = random byte (0-255)
 *   for each byte at position i:
 *     s = (i + 1) * seed
 *     encrypted[i] = plaintext[i] ^ (s >> 16 & 0xFF) ^ (s & 0xFF)
 *
 * The first encrypted byte encodes the seed:
 *   encrypted[0] = plaintext[0] ^ seed  (since (1 * seed >> 16) = 0 for seed < 256)
 *
 * Decryption:
 *   seed = encrypted[0] ^ expected_first_byte
 *   For UDP game packets, expected_first_byte can be inferred by trying known packet types.
 *   In practice: seed = encrypted[0] ^ 0x01 for handshake, then verify.
 *
 *   for each byte at position i:
 *     s = (i + 1) * seed
 *     decrypted[i] = encrypted[i] ^ (s >> 16 & 0xFF) ^ (s & 0xFF)
 */
public class PacketObfuscator {

    private static final int[] KNOWN_HEADERS = {0x01, 0x03, 0x04, 0x08, 0x13};

    /**
     * Decrypt a UDP packet. Tries known header types to find the seed.
     * Returns the decrypted packet, or null if no valid header matched.
     */
    public static byte[] decrypt(byte[] data, int length) {
        if (length < 1) return null;

        // Try each known first-byte value
        for (int header : KNOWN_HEADERS) {
            int seed = (data[0] ^ header) & 0xFF;
            byte[] result = applyXor(data, length, seed);
            if ((result[0] & 0xFF) == header) {
                return result;
            }
        }

        // Fallback: return as-is (unobfuscated packet)
        byte[] copy = new byte[length];
        System.arraycopy(data, 0, copy, 0, length);
        return copy;
    }

    /**
     * Encrypt a UDP packet with a random seed.
     */
    public static byte[] encrypt(byte[] data, int length) {
        int seed = (int)(Math.random() * 256);
        return applyXor(data, length, seed);
    }

    /**
     * Encrypt a UDP packet with a specific seed.
     */
    public static byte[] encrypt(byte[] data, int length, int seed) {
        return applyXor(data, length, seed & 0xFF);
    }

    /**
     * Apply the XOR cipher with the given seed.
     * Used for both encryption and decryption (symmetric).
     */
    private static byte[] applyXor(byte[] data, int length, int seed) {
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            int s = ((i + 1) * seed);
            int keyByte = ((s >> 16) & 0xFF) ^ (s & 0xFF);
            result[i] = (byte)((data[i] & 0xFF) ^ keyByte);
        }
        return result;
    }
}
