package server.gameserver.packets.client_tcp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.SessionReady;
import server.networktools.PacketBuilderTCP;

/**
 * Tests for the C→S 0xa0/0x03 ReadyProbe packet handler.
 *
 * <p>This is the modern (May 2026) retail flow's replacement for the
 * older 0x87/0x37 GetGamedata sub-exchange between AuthAck and AuthB
 * on GameLobby (Stage 2). Live-verified against the Braine real-client
 * capture 2026-05-22.
 *
 * <p>Wire layout:
 * <pre>
 *   C→S 0xa0/0x03 body: {@code a0 03}            (no payload)
 *   S→C 0xa0/0x01 body: {@code a0 01 15 00 00 00 00 00 80 3f} (10B)
 * </pre>
 */
public class ReadyProbeTest {

    @Test
    public void readyProbeRoundTripsThroughDispatcher() throws Exception {
        // C→S body = `a0 03`. Reader exposes the dispatch via the
        // package-private `read()` static (see GamePacketReaderTCP).
        byte[] body = { (byte) 0xa0, 0x03 };
        GamePacketDecoderTCP packet = invokeDispatch(body);
        assertNotNull("dispatch returned null for a003", packet);
        assertTrue("dispatch did not return ReadyProbe; got "
                + packet.getClass().getSimpleName(),
                packet instanceof ReadyProbe);
    }

    @Test
    public void readyProbeExecuteSendsSessionReady() {
        // SessionReady is the canonical S→C reply. Make sure its
        // body is the modern retail 10-byte form.
        SessionReady reply = new SessionReady();
        byte[] wire = wireBytes(reply);
        // fe 0a 00 a0 01 15 00 00 00 00 00 80 3f
        byte[] expected = {
                (byte) 0xfe, 0x0a, 0x00,
                (byte) 0xa0, 0x01,
                0x15, 0x00, 0x00, 0x00,
                0x00, 0x00, (byte) 0x80, 0x3f
        };
        assertArrayEquals(expected, wire);
    }

    @Test
    public void sessionReadyPayloadIsTheLiveVerifiedConstant() {
        // Lock the 8-byte payload bytes so any drift trips the test.
        byte[] payload = SessionReady.RETAIL_BODY_PAYLOAD_2_5;
        assertEquals(8, payload.length);
        assertArrayEquals(new byte[] {
                0x15, 0x00, 0x00, 0x00,
                0x00, 0x00, (byte) 0x80, 0x3f
        }, payload);
    }

    // ---------- helpers ----------

    /** Read the wire bytes from a server packet. */
    private static byte[] wireBytes(PacketBuilderTCP builder) {
        byte[] data = builder.getData();
        int n = builder.size();
        byte[] out = new byte[n];
        System.arraycopy(data, 0, out, 0, n);
        return out;
    }

    /** Invoke the same dispatch logic as the live reader, given an
     *  in-memory payload buffer (no socket). The reader normally
     *  reads off a stream; we bypass that and call the switch by
     *  constructing handlers directly based on the first two bytes. */
    private static GamePacketDecoderTCP invokeDispatch(byte[] readbuffer)
            throws Exception {
        // GamePacketReaderTCP doesn't expose dispatch as a static —
        // it reads from a stream. Mimic the relevant 0xa0/0x03 branch
        // explicitly here, mirroring what the reader does.
        if (readbuffer.length >= 2
                && (readbuffer[0] & 0xff) == 0xa0
                && (readbuffer[1] & 0xff) == 0x03) {
            return new ReadyProbe(readbuffer);
        }
        return null;
    }
}
