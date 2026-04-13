package server.networktools;

import java.util.Random;

/**
 * NC2 UDP wire encryption — per-packet LFSR with cipher-feedback.
 *
 * <p>Every UDP datagram sent by the retail server (and expected by the
 * modern NCE 2.5 client) has a 4-byte encryption header prepended:
 * <pre>
 *   [seed_lo][seed_hi][enc_len_lo][enc_len_hi][enc_data...]
 * </pre>
 *
 * <p>The seed (bytes 0-1) is a random 16-bit value sent in the clear.
 * Bytes 2+ are encrypted using a 16-bit LFSR PRNG in cipher-feedback
 * (CFB) mode: each key byte is derived from the PREVIOUS ciphertext byte.
 *
 * <p>Reverse-engineered from {@code FUN_00560090} (encrypt/sendto wrapper)
 * and {@code FUN_004e36e0} (LFSR PRNG) in neocronclient.exe.
 * See {@code docs/PROTOCOL.md} "UDP Wire Encryption" for full documentation.
 */
public final class WireEncrypt {

    private static final Random RNG = new Random();

    private WireEncrypt() {}

    /**
     * 16-bit LFSR PRNG with data feedback.
     *
     * <p>From Ghidra decompile of {@code FUN_004e36e0}:
     * for each of 8 bits, the feedback is
     * {@code (hi>>6) ^ (hi>>5) ^ (hi>>3) ^ lo ^ (input>>bit) & 1},
     * where hi/lo are the high/low bytes of the 16-bit state.
     * Output byte is assembled MSB-first from the feedback bits.
     *
     * @param state 2-element array: state[0] is the 16-bit LFSR state,
     *              updated in place after the call.
     * @param input the cipher-feedback input byte (previous ciphertext byte)
     * @return the generated key byte
     */
    public static int lfsrByte(int[] state, int input) {
        int s = state[0] & 0xFFFF;
        int output = 0;
        for (int bit = 0; bit < 8; bit++) {
            int hi = (s >> 8) & 0xFF;
            int lo = s & 0xFF;
            int dataBit = (input >> bit) & 1;
            int feedback = ((hi >> 6) ^ (hi >> 5) ^ (hi >> 3) ^ lo ^ dataBit) & 1;
            s = ((s << 1) | feedback) & 0xFFFF;
            output |= (feedback << (7 - bit)); // MSB-first
        }
        state[0] = s;
        return output & 0xFF;
    }

    /**
     * Encrypt a plaintext UDP payload into the wire format.
     *
     * @param plaintext the raw packet bytes (e.g. starts with 0x13 for gamedata)
     * @param offset    start offset into the plaintext array
     * @param length    number of plaintext bytes
     * @return encrypted wire bytes: [seed_lo][seed_hi][enc_len_lo][enc_len_hi][enc_data...]
     *         total length = plaintext length + 4
     */
    public static byte[] encrypt(byte[] plaintext, int offset, int length) {
        byte[] wire = new byte[length + 4];

        // Random 16-bit seed
        int seed = RNG.nextInt(0x10000);
        wire[0] = (byte) (seed & 0xFF);
        wire[1] = (byte) ((seed >> 8) & 0xFF);

        int[] state = {seed};

        // Encrypt the 2-byte length field
        int key = lfsrByte(state, wire[1] & 0xFF);
        wire[2] = (byte) (key ^ (length & 0xFF));

        key = lfsrByte(state, wire[2] & 0xFF);  // CFB: prev cipher byte
        wire[3] = (byte) (key ^ ((length >> 8) & 0xFF));

        // Encrypt each data byte (cipher-feedback mode)
        int prevCipher = wire[3] & 0xFF;
        for (int i = 0; i < length; i++) {
            key = lfsrByte(state, prevCipher);
            wire[4 + i] = (byte) (key ^ (plaintext[offset + i] & 0xFF));
            prevCipher = wire[4 + i] & 0xFF;
        }

        return wire;
    }

    /**
     * Convenience: encrypt a full byte array.
     */
    public static byte[] encrypt(byte[] plaintext) {
        return encrypt(plaintext, 0, plaintext.length);
    }

    /**
     * Decrypt a UDP wire packet back to plaintext.
     *
     * <p>Inverse of {@link #encrypt(byte[], int, int)}. The NC2 client
     * encrypts its C→S datagrams with the same LFSR CFB cipher, so we
     * must run the same state machine in reverse to recover the
     * plaintext payload (first byte 0x01/0x03/0x04/0x08/0x13).
     *
     * <p>Wire format:
     * <pre>
     *   [seed_lo][seed_hi][enc_len_lo][enc_len_hi][enc_data...]
     * </pre>
     *
     * @param wire   the wire bytes received from the socket
     * @param offset start offset
     * @param length number of wire bytes available
     * @return plaintext bytes (length = encrypted data length),
     *         or {@code null} if the packet is too short or the declared
     *         data length exceeds what's available (malformed packet).
     */
    public static byte[] decrypt(byte[] wire, int offset, int length) {
        if (length < 4) return null;
        int seed = (wire[offset] & 0xFF) | ((wire[offset + 1] & 0xFF) << 8);
        int[] state = {seed};

        // Decrypt 2-byte length field. For byte 2, CFB input is wire[1]
        // (seed_hi). For byte 3, CFB input is wire[2] (previous cipher
        // byte). This mirrors the encrypt path exactly.
        int key = lfsrByte(state, wire[offset + 1] & 0xFF);
        int lenLo = (key ^ (wire[offset + 2] & 0xFF)) & 0xFF;

        key = lfsrByte(state, wire[offset + 2] & 0xFF);
        int lenHi = (key ^ (wire[offset + 3] & 0xFF)) & 0xFF;

        int dataLen = (lenHi << 8) | lenLo;
        if (dataLen < 0 || dataLen > length - 4) return null;

        byte[] plain = new byte[dataLen];
        int prevCipher = wire[offset + 3] & 0xFF;
        for (int i = 0; i < dataLen; i++) {
            int cipherByte = wire[offset + 4 + i] & 0xFF;
            key = lfsrByte(state, prevCipher);
            plain[i] = (byte) (key ^ cipherByte);
            prevCipher = cipherByte;
        }
        return plain;
    }

    /**
     * Convenience: decrypt a wire byte array.
     */
    public static byte[] decrypt(byte[] wire) {
        return decrypt(wire, 0, wire.length);
    }
}
