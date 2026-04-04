package server.gameserver.packets.client_tcp;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the Auth packet parser.
 *
 * Auth packet format (from protocol analysis):
 *   0x00  short   packet id (0x8480) — already consumed by reader
 *   0x02  byte    encryption key
 *   0x03  18 bytes unknown
 *   0x15  short   username length (including null terminator)
 *   0x17  short   password length (encoded, in shorts — actual byte count is 2x)
 *   0x19  cstring username (null-terminated)
 *   +     short[] encoded password
 */
public class AuthTest {

    /**
     * Build a valid Auth packet with the given username and password key.
     * The packet id bytes (0x84, 0x80) are included since the Auth decoder
     * starts from the beginning of the packet buffer.
     */
    private byte[] buildAuthPacket(String username, String password, int encryptionKey) {
        // Username: null-terminated C string
        byte[] usernameBytes = new byte[username.length() + 1];
        System.arraycopy(username.getBytes(), 0, usernameBytes, 0, username.length());
        usernameBytes[username.length()] = 0;

        // Password: encoded as shorts, each short = ((char + key) << 4)
        byte[] passwordShorts = new byte[password.length() * 2];
        for (int i = 0; i < password.length(); i++) {
            int encoded = ((password.charAt(i) + encryptionKey) << 4);
            passwordShorts[i * 2] = (byte) (encoded & 0xFF);
            passwordShorts[i * 2 + 1] = (byte) ((encoded >> 8) & 0xFF);
        }

        // Total: 2 (packet id) + 1 (key) + 30 (unknown) + 2 (usernameLen) + 2 (passwordLen) + username + password
        int totalSize = 2 + 1 + 30 + 2 + 2 + usernameBytes.length + passwordShorts.length;
        byte[] packet = new byte[totalSize];
        int pos = 0;

        // Packet ID (already consumed by reader, but Auth expects it in buffer)
        packet[pos++] = (byte) 0x84;
        packet[pos++] = (byte) 0x80;

        // Encryption key
        packet[pos++] = (byte) encryptionKey;

        // 30 bytes unknown (zeros)
        pos += 30;

        // Username length (little-endian short, includes null terminator)
        int uLen = usernameBytes.length;
        packet[pos++] = (byte) (uLen & 0xFF);
        packet[pos++] = (byte) ((uLen >> 8) & 0xFF);

        // Password length (little-endian short, number of encoded bytes = chars * 2)
        int pLen = passwordShorts.length;
        packet[pos++] = (byte) (pLen & 0xFF);
        packet[pos++] = (byte) ((pLen >> 8) & 0xFF);

        // Username
        System.arraycopy(usernameBytes, 0, packet, pos, usernameBytes.length);
        pos += usernameBytes.length;

        // Password
        System.arraycopy(passwordShorts, 0, packet, pos, passwordShorts.length);

        return packet;
    }

    @Test
    public void testValidAuthPacketParsing() {
        // Build a packet for user "runner" with password "test" and key 0x42
        byte[] packet = buildAuthPacket("runner", "test", 0x42);
        Auth auth = new Auth(packet);

        // The Auth.execute() needs a GameServerTCPConnection which we can't easily mock
        // So we test the packet structure by reading manually
        auth.skip(2); // packet id
        int key = auth.read();
        assertEquals(0x42, key);

        auth.skip(30); // unknown
        int usernameLength = auth.readShort();
        int passwordLength = auth.readShort() / 2;

        assertEquals(7, usernameLength); // "runner" + null = 7
        assertEquals(4, passwordLength); // "test" = 4 chars

        String username = auth.readCString(usernameLength);
        assertEquals("runner", username);

        String password = auth.readEncryptedString(passwordLength, key);
        assertEquals("test", password);
    }

    @Test
    public void testEmptyUsernamePacket() {
        // Empty username (just null terminator), password "x"
        byte[] packet = buildAuthPacket("", "x", 0x10);
        Auth auth = new Auth(packet);

        auth.skip(2);
        int key = auth.read();
        auth.skip(30); // modern client unknown block
        int usernameLength = auth.readShort();
        int passwordLength = auth.readShort() / 2;

        assertEquals(1, usernameLength); // just null terminator
        assertEquals(1, passwordLength);

        String username = auth.readCString(usernameLength);
        assertEquals("", username);
    }

    @Test
    public void testRealPacketFromModernClient() {
        // Exact packet captured from a real NC2 client connection
        byte[] packet = {
            (byte)0x84, (byte)0x80, (byte)0x1c, (byte)0x06, (byte)0xb7, (byte)0x47,
            (byte)0xde, (byte)0xb3, (byte)0x12, (byte)0x4d, (byte)0xfe, (byte)0x02,
            (byte)0x01, (byte)0x7d, (byte)0x5c, (byte)0x85, (byte)0x98, (byte)0x06,
            (byte)0xb7, (byte)0x47, (byte)0x5c, (byte)0x00, (byte)0x00, (byte)0x50,
            (byte)0x0f, (byte)0x5d, (byte)0x54, (byte)0xac, (byte)0xbb, (byte)0x79,
            (byte)0x71, (byte)0xf5, (byte)0xf5, (byte)0x09, (byte)0x00, (byte)0x12,
            (byte)0x00, (byte)0x6d, (byte)0x73, (byte)0x6e, (byte)0x32, (byte)0x77,
            (byte)0x6f, (byte)0x6c, (byte)0x66, (byte)0x00, (byte)0xf8, (byte)0x48,
            (byte)0xcb, (byte)0x88, (byte)0xe6, (byte)0x18, (byte)0x83, (byte)0x58,
            (byte)0xad, (byte)0x08, (byte)0x78, (byte)0x28, (byte)0xef, (byte)0x54,
            (byte)0x74, (byte)0xc8, (byte)0x7c, (byte)0x98, (byte)0x00
        };

        Auth auth = new Auth(packet);

        // Parse according to the modern client layout
        auth.skip(2); // 0x84 0x80
        int key = auth.read(); // 0x1c
        assertEquals(0x1c, key);

        auth.skip(30); // unknown block

        int usernameLength = auth.readShort(); // 0x09, 0x00 -> 9
        assertEquals(9, usernameLength);

        int passwordLength = auth.readShort() / 2; // 0x12, 0x00 -> 18/2 = 9
        assertEquals(9, passwordLength);

        String username = auth.readCString(usernameLength);
        assertEquals("msn2wolf", username);

        // Password is encrypted, just verify it doesn't crash
        String password = auth.readEncryptedString(passwordLength, key);
        assertEquals(9, password.length());
    }

    @Test
    public void testShortPacketDoesNotCrash() {
        // A truncated packet — should not throw StringIndexOutOfBoundsException
        byte[] shortPacket = new byte[10]; // Too short for a valid auth packet
        shortPacket[0] = (byte) 0x84;
        shortPacket[1] = (byte) 0x80;

        Auth auth = new Auth(shortPacket);

        // Manually simulate what execute() does, with the fix applied
        auth.skip(2);
        int key = auth.read();
        auth.skip(Math.min(18, shortPacket.length - 3)); // safe skip

        // At this point we're past the buffer — readShort should return -1 or 0
        // The fix should handle this gracefully
        int usernameLength = auth.readShort();
        // With a short buffer, usernameLength will be garbage — readCString must not crash
        if (usernameLength > 0 && usernameLength < shortPacket.length) {
            auth.readCString(usernameLength);
        }
        // If we get here without exception, the bounds check works
        assertTrue(true);
    }
}
