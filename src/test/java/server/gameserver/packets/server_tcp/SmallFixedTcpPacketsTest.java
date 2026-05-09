package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;

import org.junit.Test;

/**
 * Byte-identical pin tests for the small fixed-size TCP S→C
 * packets that have just an opcode and no body (or a 2-byte
 * body). Each is verified against the auto-generated catalog
 * at {@code docs/protocol/_data/packets.json}; in every case
 * 100% of retail samples are byte-identical.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code Gamedata} (0x87 0x3a) — 12 samples × {@code 873a}</li>
 *   <li>{@code SessionReady} (0xa0 0x01) — 34 samples × {@code a001}</li>
 *   <li>{@code TcpKeepalive} (0x83 0x8f) — same bytes as
 *       {@link Packet838F} but kept as a separate class for the
 *       periodic emitter; pin both</li>
 * </ul>
 *
 * <p>Pinning these now means refactors of {@link
 * server.networktools.PacketBuilderTCP}'s framing path cannot
 * regress these login-critical bytes.
 */
public class SmallFixedTcpPacketsTest {

    private static byte[] wireBytes(ByteArrayOutputStream pkt) {
        byte[] data = ((server.networktools.PacketBuilderTCP) pkt).getData();
        int n = pkt.size();
        byte[] sliced = new byte[n];
        System.arraycopy(data, 0, sliced, 0, n);
        return sliced;
    }

    @Test
    public void gamedataExactBytes() {
        // Catalog body `873a` framed = fe 02 00 87 3a
        byte[] expected = { (byte) 0xfe, 0x02, 0x00, (byte) 0x87, 0x3a };
        assertArrayEquals(expected, wireBytes(new Gamedata()));
    }

    @Test
    public void sessionReadyExactBytes() {
        // Catalog body `a001` framed = fe 02 00 a0 01
        byte[] expected = { (byte) 0xfe, 0x02, 0x00, (byte) 0xa0, 0x01 };
        assertArrayEquals(expected, wireBytes(new SessionReady()));
    }

    @Test
    public void tcpKeepaliveExactBytes() {
        // Catalog body `838f0000000000` framed = fe 07 00 83 8f 00 00 00 00 00
        // Same wire output as Packet838F — the duplication is
        // intentional pending the open question of whether
        // 0x83/0x8f is a true periodic keepalive (existing
        // interpretation) or strictly an interaction-commit ack
        // (catalog's marker correlation suggests the latter).
        // Whatever the semantic, the bytes are pinned.
        byte[] expected = {
                (byte) 0xfe, 0x07, 0x00,
                (byte) 0x83, (byte) 0x8f,
                0x00, 0x00, 0x00, 0x00, 0x00
        };
        assertArrayEquals(expected, wireBytes(new TcpKeepalive()));
    }

    @Test
    public void tcpKeepaliveBytesEqualPacket838F() {
        // The pure-bytes equality is the single fact a future
        // dedupe must preserve. If this assertion ever fails,
        // we've drifted: either the duplication was reconciled
        // (good — remove this test along with the duplicate
        // class) or the bytes diverged (bad — investigate).
        byte[] keepalive  = wireBytes(new TcpKeepalive());
        byte[] packet838f = wireBytes(new Packet838F());
        assertArrayEquals("TcpKeepalive and Packet838F must "
                + "remain byte-identical until one is retired",
                keepalive, packet838f);
    }

    @Test
    public void allSmallFixedPacketsHaveExpectedTotalSize() {
        // Defensive: catch any framing regression that adds or
        // drops bytes silently.
        assertEquals("Gamedata = 5B framed",
                5, new Gamedata().size());
        assertEquals("SessionReady = 5B framed",
                5, new SessionReady().size());
        assertEquals("TcpKeepalive = 10B framed",
                10, new TcpKeepalive().size());
    }
}
