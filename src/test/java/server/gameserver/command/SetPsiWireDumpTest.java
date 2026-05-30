package server.gameserver.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.AdminCommandHandler;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.CharInfo;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.PoolStatusBroadcast;
import server.gameserver.packets.server_udp.PoolUpdate;
import server.interfaces.ServerUDPPacket;
import server.testtools.CapturingUDPConnection;

/**
 * Diagnostic + regression test for the {@code .setpsi 0} wire
 * emission (task #207).
 *
 * <p>Captures every UDP packet the admin command emits and pins
 * each one's byte sequence with descriptive assertions. The exact
 * hex strings serve as a <b>frozen reference</b>: when the user
 * captures a live in-game {@code .setpsi 0} via tcpdump, a one-step
 * diff against the bytes pinned here reveals whether the wire
 * matches expectation or where it diverges. The narrowed-suspect
 * memory ({@code cross-pool-collateral-narrowed}) documents that
 * server-side wire is byte-correct in isolation; this test pins the
 * full {@code cmdSetPsi(0)} sequence so we can rule out emission-
 * order or interaction bugs vs client-side.
 *
 * <p>Expected emission contract (from
 * {@link AdminCommandHandler#cmdSetPsi}):
 * <ol>
 *   <li>{@link PoolUpdate} (raw 0x1f, 16-byte body) — POOL_PSI delta</li>
 *   <li>{@link PoolStatusBroadcast} (raw 0x1f, 14-byte body) —
 *       all-pool snapshot</li>
 *   <li>{@link CharInfo} (reliable 0x03/0x2c v0x02 single packet) —
 *       full live-CHARSYS resync</li>
 * </ol>
 *
 * <p>If a future refactor changes the emission count or order, this
 * test fails loudly and the developer is forced to update the
 * contract here too.
 */
public class SetPsiWireDumpTest {

    private static byte[] datagramBytes(ServerUDPPacket pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    /** Set the legacy {@code Transactionid} field directly so test
     *  output is deterministic across runs (the CharInfo TX bytes
     *  depend on this counter). */
    private static void setTxn(Player pl, int v) throws Exception {
        Field f = Player.class.getDeclaredField("Transactionid");
        f.setAccessible(true);
        f.setShort(pl, (short) v);
    }

    /** Capture all UDP emissions from a single {@code .setpsi 0} call. */
    private static List<byte[]> captureSetPsiZero(int curHp, int maxHp,
                                                  int curPsi, int maxPsi,
                                                  int curSta, int maxSta)
            throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setHealth(curHp);
        pc.setMaxHealth(maxHp);
        pc.setPsi(curPsi);
        pc.setMaxPsi(maxPsi);
        pc.setStamina(curSta);
        pc.setMaxStamina(maxSta);
        setTxn(pl, 0);

        CapturingUDPConnection cap = CapturingUDPConnection.replaceOn(pl);
        boolean handled = AdminCommandHandler.handle(pl, ".setpsi 0");
        assertTrue("AdminCommandHandler must accept '.setpsi 0'",
                handled);
        return cap.received().stream()
                .map(SetPsiWireDumpTest::datagramBytes)
                .collect(Collectors.toList());
    }

    @Test
    public void setPsiZeroEmitsThreePackets() throws Exception {
        // Contract: cmdSetPsi sends [PoolUpdate, PoolStatusBroadcast,
        // CharInfo via LiveCharInfoSync]. A regression that adds a
        // 4th or drops one would mask a wire bug.
        List<byte[]> pkts = captureSetPsiZero(
                200, 250, 150, 150, 80, 100);
        assertEquals(
                "expected 3 UDP packets from '.setpsi 0'; got "
                + pkts.size() + " (hex dump per packet below)\n"
                + pkts.stream().map(SetPsiWireDumpTest::hex)
                        .collect(Collectors.joining("\n  ", "  ", "")),
                3, pkts.size());
    }

    @Test
    public void firstPacketIsPoolUpdatePsiWithCorrectDelta()
            throws Exception {
        // PoolUpdate body layout (verified retail-byte-identical in
        // PoolUpdateByteIdentityTest): 16 bytes inside the 0x13 wrapper.
        //   1f 01 00 50 [delta LE4] 00 00 00 [pool] [max LE2] 00 00
        // delta = target - cur = 0 - 150 = -150 = 0xFFFFFF6A
        // pool = POOL_PSI = 0x05
        // max = maxPsi = 150 = 0x0096
        List<byte[]> pkts = captureSetPsiZero(
                200, 250, 150, 150, 80, 100);
        byte[] body = extractInnerBody(pkts.get(0), 16);
        assertEquals("packet[0] is PoolUpdate (1f 01 00 50)",
                "1f 01 00 50", hex(slice(body, 0, 4)));
        assertEquals("delta LE32 = -150 (0xFFFFFF6A)",
                "6a ff ff ff", hex(slice(body, 4, 4)));
        assertEquals("3 zero padding bytes",
                "00 00 00", hex(slice(body, 8, 3)));
        assertEquals("pool byte = POOL_PSI (0x05)",
                0x05, body[11] & 0xff);
        assertEquals("maxPsi LE16 = 150 (0x0096)",
                "96 00", hex(slice(body, 12, 2)));
        assertEquals("trailing zeros",
                "00 00", hex(slice(body, 14, 2)));
    }

    @Test
    public void secondPacketIsPoolStatusBroadcastWithPsiZero()
            throws Exception {
        // PoolStatusBroadcast body layout (verified in
        // PoolStatusBroadcastByteIdentityTest): 14 bytes inside the
        // 0x13 wrapper.
        //   1f 01 00 30 [HP LE2] [PSI LE2] [STA LE2] [maxHP LE2] [maxHP LE2]
        // After .setpsi 0: HP=200, PSI=0, STA=80, maxHP=250 (×2)
        List<byte[]> pkts = captureSetPsiZero(
                200, 250, 150, 150, 80, 100);
        byte[] body = extractInnerBody(pkts.get(1), 14);
        assertEquals("packet[1] is PoolStatusBroadcast (1f 01 00 30)",
                "1f 01 00 30", hex(slice(body, 0, 4)));
        assertEquals("HP LE16 = 200 (0x00C8)",
                "c8 00", hex(slice(body, 4, 2)));
        assertEquals("PSI LE16 = 0 (after setpsi 0)",
                "00 00", hex(slice(body, 6, 2)));
        assertEquals("STA LE16 = 80 (0x0050) — must NOT be zero",
                "50 00", hex(slice(body, 8, 2)));
        assertEquals("maxHP LE16 slot 1 = 250 (0x00FA)",
                "fa 00", hex(slice(body, 10, 2)));
        assertEquals("maxHP LE16 slot 2 (repeated) = 250",
                "fa 00", hex(slice(body, 12, 2)));
    }

    @Test
    public void thirdPacketIsCharInfoSyncWithSection2PoolsCorrect()
            throws Exception {
        // CharInfo is wrapped via 0x03/0x2c reliable variant 0x02;
        // it carries the full CHARSYS sections. We only assert
        // section 2's pool fields here (the rest is covered by
        // CharInfoContentTest). The pinned bytes ARE the canonical
        // wire after setpsi 0.
        List<byte[]> pkts = captureSetPsiZero(
                200, 250, 150, 150, 80, 100);
        byte[] dat = pkts.get(2);
        // The reliable wrapper makes finding section 2 inside a
        // multi-section payload non-trivial; assert at minimum that
        // (a) the packet exists, (b) the cur PSI=0 bytes (00 00)
        // appear at the expected POSITION inside the CHARSYS prelude,
        // and (c) cur STA=80 (50 00) also appears. The CharInfo
        // section structure prefix is well-known (see
        // CharInfoContentTest.extractSections logic).
        assertNotNull("packet[2] must not be null", dat);
        assertTrue("packet[2] non-empty", dat.length > 0);
        // The CharInfo Section 2 cur/max loop interleaves
        // (HP cur, HP max, PSI cur, PSI max, STA cur, STA max).
        // After .setpsi 0 with our fixture, that's bytes:
        //   c8 00  fa 00  00 00  96 00  50 00  64 00
        //  (HP=200,maxHP=250,PSI=0,maxPSI=150,STA=80,maxSTA=100)
        // Asserting this exact 12-byte run catches:
        //   (a) HP/PSI/STA bytes correctly land in pairs
        //   (b) PSI = 0 (the setpsi result)
        //   (c) STA bytes UNCHANGED at 80/100 (no cross-pool collateral
        //       in CharInfo Section 2 — same property already pinned
        //       in CharInfoContentTest, re-asserted here in the full
        //       cmdSetPsi context)
        byte[] expectedPoolRun = {
                (byte) 0xc8, 0x00,   // HP cur = 200
                (byte) 0xfa, 0x00,   // HP max = 250
                0x00, 0x00,           // PSI cur = 0 (after setpsi 0)
                (byte) 0x96, 0x00,   // PSI max = 150
                0x50, 0x00,           // STA cur = 80 — MUST NOT zero
                0x64, 0x00            // STA max = 100
        };
        assertTrue("CharInfo Section 2 pool block must be the "
                + "interleaved cur/max sequence "
                + "c8 00 fa 00 00 00 96 00 50 00 64 00 "
                + "(HP=200 maxHP=250 PSI=0 maxPSI=150 STA=80 maxSTA=100)\n"
                + "full packet: " + hex(dat),
                containsByteRun(dat, expectedPoolRun));
    }

    @Test
    public void diagnosticHexDumpHelpsLiveComparison() throws Exception {
        // This test ALWAYS passes — its job is to print the canonical
        // wire bytes to stdout so a developer can diff them against
        // an in-game pcap. Pair with tools/pcap-session.sh + pcap-
        // decode.py. Comment out @Test or run with -Dtest.dump=1
        // when looking at the output.
        List<byte[]> pkts = captureSetPsiZero(
                200, 250, 150, 150, 80, 100);
        if (!"1".equals(System.getProperty("test.dump"))) {
            return;
        }
        System.out.println("---- .setpsi 0 wire dump ----");
        System.out.println("setup: HP=200/250 PSI=150/150 STA=80/100");
        for (int i = 0; i < pkts.size(); i++) {
            byte[] b = pkts.get(i);
            System.out.println("packet[" + i + "] (" + b.length + "B):");
            System.out.println("  " + hex(b));
        }
    }

    // ──────────────────────────────────────── helpers

    /** Extract the {@code len}-byte inner body from a {@code 0x13}-
     *  wrapped raw packet. Body starts at offset 7 (after 0x13 +
     *  counter LE2 + counter+sk LE2 + size LE2). */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13", 0x13, datagram[0] & 0xff);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 7, body, 0, len);
        return body;
    }

    private static byte[] slice(byte[] src, int from, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, from, out, 0, len);
        return out;
    }

    private static boolean containsByteRun(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
