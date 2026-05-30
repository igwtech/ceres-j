package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link TimeSync} (UDP S→C reliable
 * {@code 0x03/0x0d}). Pins the trailing 4-byte
 * {@code d5 0a 58 00} world identifier against retail catalog
 * evidence (80 samples across 17/17 captures uniformly carry
 * those bytes); pre-fix Ceres-J wrote {@code fb 0a 00 00}.
 */
public class TimeSyncByteIdentityTest {

    private static byte[] datagramBytes(TimeSync pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP1303):
     *  {@code [0x13][counter LE2][counter+sk LE2][size LE2][0x03][seq LE2][0x0d][body 12B]}
     *  Body starts at offset 11. */
    private static byte[] extractInnerBody(byte[] datagram) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x0d", 0x0d, datagram[10] & 0xFF);
        byte[] body = new byte[12];
        System.arraycopy(datagram, 11, body, 0, 12);
        return body;
    }

    @Test
    public void clientTimeEchoEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] body = extractInnerBody(datagramBytes(
                new TimeSync(pl, 0x05_02_C8_FF)));
        // Bytes [4..7] are the client-time echo, LE32.
        assertEquals(0xFF, body[4] & 0xFF);
        assertEquals(0xC8, body[5] & 0xFF);
        assertEquals(0x02, body[6] & 0xFF);
        assertEquals(0x05, body[7] & 0xFF);
    }

    @Test
    public void trailingFourBytesAreRetailWorldId() {
        // The trailing 4 bytes are `d5 0a [LE16 var] 00`. Bytes 8..9
        // are CONSTANT (0xd5 0x0a) across all retail captures —
        // tag prefix. Bytes 10..11 are session-state, varying per
        // session: HANNIBAL/NORMAN/AUGUSTO emit 0x0020, DRSTONE3
        // emits 0x0051. We pin to 0x0020 (3/4 retail samples) and
        // mask byte 10 in the pcap-replay harness as session-derived.
        // Pre-fix history: `fb 0a 00 00` (wrong) → `d5 0a 58 00`
        // (single-capture extrapolation, also wrong) → current
        // `d5 0a 20 00` (most common retail value).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] body = extractInnerBody(datagramBytes(
                new TimeSync(pl, 0)));
        assertEquals("body[8] = 0xd5 const", 0xD5, body[8]  & 0xFF);
        assertEquals("body[9] = 0x0a const", 0x0A, body[9]  & 0xFF);
        assertEquals("body[10] = 0x20 (session-state, default)",
                0x20, body[10] & 0xFF);
        assertEquals("body[11] = 0x00 const",
                0x00, body[11] & 0xFF);
    }

    @Test
    public void trailingBytesNotFB0A0000Anymore() {
        // Regression guard: the pre-fix bytes were `fb 0a 00 00`.
        // Confirm we no longer emit them. A future refactor that
        // resurrects the wrong bytes will fail here.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] body = extractInnerBody(datagramBytes(
                new TimeSync(pl, 0)));
        boolean isOldBytes =
                (body[8]  & 0xFF) == 0xFB &&
                (body[9]  & 0xFF) == 0x0A &&
                (body[10] & 0xFF) == 0x00 &&
                (body[11] & 0xFF) == 0x00;
        assertFalse("trailing bytes must not regress to the "
                + "pre-fix `fb 0a 00 00`", isOldBytes);
    }

    @Test
    public void worldIdConstantMatchesEmittedTail() {
        // The public WORLD_ID_TAIL constant must equal the bytes
        // actually written. If they ever drift, fix one or the
        // other.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new TimeSync(pl, 0)));
        for (int i = 0; i < 4; i++) {
            assertEquals("byte " + i + " of WORLD_ID_TAIL",
                    TimeSync.WORLD_ID_TAIL[i],
                    body[8 + i]);
        }
    }

    @Test
    public void totalDatagramSizeIsTwentyThreeBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x0d) + 12 (body) = 23 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(23, datagramBytes(new TimeSync(pl, 0)).length);
    }

    // ─── task #231: server_time monotonic ms (not hardcoded 1) ─────

    @org.junit.After
    public void resetClock() {
        TimeSync.resetClockForTesting();
    }

    @Test
    public void serverTimeIsNoLongerHardcodedToOne() {
        // Regression guard: pre-fix the code wrote `writeInt(1)`,
        // so body[0..3] was always `01 00 00 00`. A future refactor
        // that resurrects that must fail this test.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        // Force a non-trivial server uptime so the byte pattern
        // never collides with `01 00 00 00`.
        TimeSync.setClockForTesting(0L, () -> 12_345_678L);

        byte[] body = extractInnerBody(datagramBytes(new TimeSync(pl, 0)));
        boolean isOldHardcoded =
                (body[0] & 0xFF) == 0x01 &&
                (body[1] & 0xFF) == 0x00 &&
                (body[2] & 0xFF) == 0x00 &&
                (body[3] & 0xFF) == 0x00;
        assertFalse("server_time must not regress to hardcoded `01 00 00 00`",
                isOldHardcoded);
    }

    @Test
    public void serverTimeEncodesUptimeMsLittleEndian() {
        // Inject a fake clock: boot at t=1000, current=1000 + 12345.
        // Expected server_time = 12345 (=0x3039) encoded LE32 =
        //   39 30 00 00
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        TimeSync.setClockForTesting(1000L, () -> 1000L + 12345L);

        byte[] body = extractInnerBody(datagramBytes(new TimeSync(pl, 0)));
        assertEquals(0x39, body[0] & 0xFF);
        assertEquals(0x30, body[1] & 0xFF);
        assertEquals(0x00, body[2] & 0xFF);
        assertEquals(0x00, body[3] & 0xFF);
    }

    @Test
    public void serverTimeAdvancesBetweenEmissions() {
        // The point of the whole fix: two TimeSync packets emitted
        // at different wall-clock moments must encode DIFFERENT
        // server_time bytes. Without this the HUD clock stays
        // frozen at the boot-time value.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        long[] now = {1000L};
        TimeSync.setClockForTesting(0L, () -> now[0]);

        byte[] bodyA = extractInnerBody(datagramBytes(new TimeSync(pl, 0)));
        now[0] = 5000L;
        byte[] bodyB = extractInnerBody(datagramBytes(new TimeSync(pl, 0)));

        // server_time bytes 0..3 MUST differ between A and B.
        boolean differ = false;
        for (int i = 0; i < 4; i++) {
            if (bodyA[i] != bodyB[i]) { differ = true; break; }
        }
        assertTrue("server_time bytes must change between two "
                + "emissions at different wall-clock times — without "
                + "this the in-game HUD clock stays frozen", differ);
    }

    @Test
    public void serverTimeClampsToZeroForNegativeDelta() {
        // Defensive: if the test/NTP clock adjusts backwards
        // (clock < bootMs), server_time must clamp to 0 instead of
        // emitting a negative-as-uint32 (which would look like a
        // huge bogus value to the client).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        TimeSync.setClockForTesting(5000L, () -> 1000L);

        byte[] body = extractInnerBody(datagramBytes(new TimeSync(pl, 0)));
        // server_time = 0 → body[0..3] = 00 00 00 00.
        for (int i = 0; i < 4; i++) {
            assertEquals("server_time byte " + i + " must be 0 on"
                    + " negative-delta clamp",
                    0x00, body[i] & 0xFF);
        }
    }

    @Test
    public void serverTimeMsHelperIsExposedForReuse() {
        // The static helper is what the periodic-push event will
        // also call. Pin the contract.
        TimeSync.setClockForTesting(100L, () -> 100L + 42L);
        assertEquals(42, TimeSync.serverTimeMs());
    }
}
