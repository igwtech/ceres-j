package server.gameserver.packets.client_tcp;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import server.gameserver.CapturingTCPConnection;
import server.gameserver.packets.server_tcp.HandshakeC;
import server.interfaces.ServerTCPPacket;

/**
 * Functional test for {@link HandshakeB} — the second leg of
 * the login handshake triplet (C→S {@code 0x80 0x00 0x78}).
 * The handler responds with {@link HandshakeC} (S→C
 * {@code 0x80 0x03 0x68}).
 *
 * <p>This is one of the very first packets in any session and
 * has no Player attached yet (the GameServerTCPConnection
 * runs handshake before authentication).
 */
public class HandshakeBTest {

    /** Raw HandshakeB body — the 3-byte client handshake.
     *  HandshakeB extends GamePacketDecoderTCP which doesn't
     *  parse the body; the bytes here are illustrative. */
    private static byte[] body() {
        return new byte[]{(byte) 0x80, 0x00, 0x78};
    }

    @Test
    public void executeEmitsHandshakeC() {
        CapturingTCPConnection cap = new CapturingTCPConnection();
        new HandshakeB(body()).execute(cap);

        List<ServerTCPPacket> sent = cap.received();
        assertEquals("expected exactly one TCP response",
                1, sent.size());
        assertTrue("response must be HandshakeC, got "
                + sent.get(0).getClass().getName(),
                sent.get(0) instanceof HandshakeC);
    }

    @Test
    public void handshakeCResponseHasRetailExactBytes() {
        CapturingTCPConnection cap = new CapturingTCPConnection();
        new HandshakeB(body()).execute(cap);

        ServerTCPPacket resp = cap.received().get(0);
        byte[] data = resp.getData();
        // Catalog body for HandshakeC is `80 03 68` framed = fe 03 00 80 03 68
        assertEquals((byte) 0xfe, data[0]);
        assertEquals(3, data[1] & 0xFF);
        assertEquals(0, data[2] & 0xFF);
        assertEquals((byte) 0x80, data[3]);
        assertEquals(0x03, data[4] & 0xFF);
        assertEquals(0x68, data[5] & 0xFF);
    }

    @Test
    public void multipleInvocationsAreIdempotent() {
        // Two sequential HandshakeB executes on the same
        // connection produce two HandshakeC responses (the
        // handler is stateless).
        CapturingTCPConnection cap = new CapturingTCPConnection();
        new HandshakeB(body()).execute(cap);
        new HandshakeB(body()).execute(cap);

        assertEquals(2, cap.received().size());
        assertTrue(cap.received().get(0) instanceof HandshakeC);
        assertTrue(cap.received().get(1) instanceof HandshakeC);
    }

    @Test
    public void bodyContentDoesNotAffectResponse() {
        // The decoder ignores the body bytes — any non-empty
        // input produces the same HandshakeC response.
        CapturingTCPConnection cap1 = new CapturingTCPConnection();
        CapturingTCPConnection cap2 = new CapturingTCPConnection();

        new HandshakeB(body()).execute(cap1);
        new HandshakeB(new byte[]{0x42, 0x42, 0x42}).execute(cap2);

        assertEquals(1, cap1.received().size());
        assertEquals(1, cap2.received().size());
        // Both responses have identical wire bytes (HandshakeC
        // is constant).
        assertArrayEquals(cap1.received().get(0).getData(),
                cap2.received().get(0).getData());
    }
}
