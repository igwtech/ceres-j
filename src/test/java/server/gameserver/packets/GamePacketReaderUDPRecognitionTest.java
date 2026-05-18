package server.gameserver.packets;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import org.junit.Test;

import server.gameserver.packets.client_udp.UnknownClientUDPPacket;
import server.interfaces.GameServerEvent;

/**
 * Regression tests for the packet-recognition cases that surfaced
 * during live client testing — without these the log filled with
 * "Unknown UDP13 Packet" lines at ~90 Hz per player.
 *
 * <p>Reaches into the package-private {@code decodesub13} via
 * reflection (no public test seam exists for it). If
 * {@code decodesub13} is ever made public, the reflection here can
 * just disappear.
 */
public class GamePacketReaderUDPRecognitionTest {

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s+", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static GameServerEvent decode(byte[] subPacket) throws Exception {
        Method m = GamePacketReaderUDP.class.getDeclaredMethod(
                "decodesub13", byte[].class);
        m.setAccessible(true);
        return (GameServerEvent) m.invoke(null, (Object) subPacket);
    }

    // ─── 0x02 ack-channel + 0x3d/0x11 in-flight heartbeat ──────────

    @Test
    public void ackChannelHeartbeatIsRecognisedNotUnknown() throws Exception {
        // Live live-test capture from msn4wolf:
        // 02 [seq_lo] [seq_hi] 1f 00 00 3d 11 00 00 00 00
        byte[] sub = hex("02 02 02 1f 00 00 3d 11 00 00 00 00");
        GameServerEvent ev = decode(sub);
        // Recognised → returns null (no event posted, no logging).
        assertNull("0x02/3d/11 heartbeat must be recognised, not Unknown",
                ev);
    }

    @Test
    public void ackChannelStatusVariantIsAlsoRecognised() throws Exception {
        // 0x3d sub-tag 0x32 is the status snapshot variant — observed
        // in the live capture as 02 02 00 1f 00 00 3d 32 00 00 00 ff ff ff ff
        byte[] sub = hex(
            "02 02 00 1f 00 00 3d 32 00 00 00 ff ff ff ff");
        GameServerEvent ev = decode(sub);
        assertNull(ev);
    }

    @Test
    public void reliableChannel0x03Heartbeat0x3dIsRecognised() throws Exception {
        // Same heartbeat tag (0x3d) over the regular 0x03 reliable
        // channel — should also be recognised.
        byte[] sub = hex("03 02 02 1f 00 00 3d 11 00 00 00 00");
        GameServerEvent ev = decode(sub);
        assertNull(ev);
    }

    // ─── Existing dispatchers must still produce events ────────────

    @Test
    public void unknownTopByteStillReturnsUnknown() throws Exception {
        // 0xff isn't 0x02 / 0x03 / 0x0b / 0x0c / 0x20 / 0x2a — should
        // fall to the default "Unknown" path.
        byte[] sub = hex("ff 00 00 00");
        GameServerEvent ev = decode(sub);
        assertNotNull(ev);
        assertTrue("expected Unknown, got " + ev.getClass().getName(),
                ev instanceof UnknownClientUDPPacket);
    }

    @Test
    public void cPingUnchanged() throws Exception {
        // 0x0b CPing is one of the existing recognised top-bytes.
        byte[] sub = hex("0b 65 d0 6d 00 35 6e ab 04");
        GameServerEvent ev = decode(sub);
        assertNotNull(ev);
        assertEquals("server.gameserver.packets.client_udp.CPing",
                ev.getClass().getName());
    }

    @Test
    public void unknownInnerSubOpcodeFallsThroughToUnknown() throws Exception {
        // 0x02 outer, but inner sub-opcode is something we don't
        // route (e.g. 0xee). The fall-through logic should still
        // produce an Unknown so the developer sees what's missing.
        byte[] sub = hex("02 00 00 ee 00 00");
        GameServerEvent ev = decode(sub);
        assertNotNull(ev);
        assertTrue(ev instanceof UnknownClientUDPPacket);
    }

    // ─── task #188: raw 0x1f interaction poll + 0x3c telemetry ─────

    @Test
    public void rawInteractionPollIsRecognisedNotUnknown() throws Exception {
        // RETAIL_LIVE_p1p3_sit_npc_20260517.pcap: C→S raw poll
        // `1f 2b 01 55` (also seen with ids 0xd5/0x0a/0x0b). Must
        // route to InteractionPoll, NOT Unknown.
        byte[] sub = hex("1f 2b 01 55");
        GameServerEvent ev = decode(sub);
        assertNotNull(ev);
        assertEquals(
            "server.gameserver.packets.client_udp.InteractionPoll",
            ev.getClass().getName());
    }

    @Test
    public void rawInteractionPollOtherIdsAlsoRecognised() throws Exception {
        for (String h : new String[] {
                "1f d5 01 55", "1f 0a 01 55", "1f 0b 01 55" }) {
            GameServerEvent ev = decode(hex(h));
            assertNotNull(h, ev);
            assertEquals(h,
                "server.gameserver.packets.client_udp.InteractionPoll",
                ev.getClass().getName());
        }
    }

    @Test
    public void malformedRaw1fFallsThroughToUnknown() throws Exception {
        // Wrong length / wrong constant bytes must NOT be treated as
        // a poll — defensive shape guard.
        assertTrue(decode(hex("1f 2b 01 55 00"))
                instanceof UnknownClientUDPPacket);
        assertTrue(decode(hex("1f 2b 00 55"))
                instanceof UnknownClientUDPPacket);
        assertTrue(decode(hex("1f 2b 01 99"))
                instanceof UnknownClientUDPPacket);
    }

    @Test
    public void rawTelemetry3cIsRecognisedNotUnknown() throws Exception {
        // RETAIL_LIVE_p1p3_sit_npc_20260517.pcap: C→S raw telemetry
        // `3c 03 00 05 0b000000 006cae46` (12 B). Must route to
        // ClientTelemetry3c, NOT Unknown.
        byte[] sub = hex("3c 03 00 05 0b 00 00 00 00 6c ae 46");
        GameServerEvent ev = decode(sub);
        assertNotNull(ev);
        assertEquals(
            "server.gameserver.packets.client_udp.ClientTelemetry3c",
            ev.getClass().getName());
    }

    @Test
    public void malformedRaw3cFallsThroughToUnknown() throws Exception {
        // Wrong length / byte[2] != 0x00 must not be treated as
        // telemetry.
        assertTrue(decode(hex("3c 03 00 05 0b 00 00 00 00 6c ae"))
                instanceof UnknownClientUDPPacket);
        assertTrue(decode(hex("3c 03 ff 05 0b 00 00 00 00 6c ae 46"))
                instanceof UnknownClientUDPPacket);
    }
}
