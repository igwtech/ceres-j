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
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.ServerUDPPacket;
import server.testtools.CapturingUDPConnection;

/**
 * Mirror of {@link SetPsiWireDumpTest} — pins the wire bytes
 * {@code cmdSetSta(0)} emits. Together with the {@code .setpsi 0}
 * dump this gives both halves of the #207 cross-pool collateral
 * reference. The user-reported bug is symmetric (".setsta 0
 * lowers PSI+STA"); pinning both wires here means a single
 * in-game capture session can be diffed against both references.
 *
 * <p>Expected emission contract (same shape as cmdSetPsi):
 * <ol>
 *   <li>{@link server.gameserver.packets.server_udp.PoolUpdate}
 *       — POOL_STA delta (pool byte = 0x06)</li>
 *   <li>{@link server.gameserver.packets.server_udp.PoolStatusBroadcast}
 *       — all-pool snapshot with STA mutated</li>
 *   <li>{@link server.gameserver.packets.server_udp.CharInfo}
 *       — full live-CHARSYS resync</li>
 * </ol>
 *
 * <p>The user-bug claim is "{@code .setsta 0} lowers BOTH PSI
 * and STA on the HUD". The parallel test in {@link
 * SetPsiWireDumpTest#secondPacketIsPoolStatusBroadcastWithPsiZero}
 * already proved {@code .setpsi 0} doesn't zero STA on the wire;
 * here we prove the symmetric assertion that {@code .setsta 0}
 * doesn't zero PSI on the wire.
 */
public class SetStaWireDumpTest {

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

    private static void setTxn(Player pl, int v) throws Exception {
        Field f = Player.class.getDeclaredField("Transactionid");
        f.setAccessible(true);
        f.setShort(pl, (short) v);
    }

    private static List<byte[]> captureSetStaZero(int curHp, int maxHp,
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
        boolean handled = AdminCommandHandler.handle(pl, ".setsta 0");
        assertTrue("AdminCommandHandler must accept '.setsta 0'",
                handled);
        return cap.received().stream()
                .map(SetStaWireDumpTest::datagramBytes)
                .collect(Collectors.toList());
    }

    @Test
    public void setStaZeroEmitsThreePackets() throws Exception {
        List<byte[]> pkts = captureSetStaZero(
                200, 250, 150, 150, 80, 100);
        assertEquals("expected 3 UDP packets from '.setsta 0'",
                3, pkts.size());
    }

    @Test
    public void firstPacketIsPoolUpdateStaWithCorrectDelta()
            throws Exception {
        // PoolUpdate: 1f 01 00 50 [delta LE4] 00 00 00 [pool] [max LE2] 00 00
        // delta = 0 - 80 = -80 = 0xFFFFFFB0
        // pool = POOL_STA = 0x06
        // max = maxSta = 100 = 0x0064
        List<byte[]> pkts = captureSetStaZero(
                200, 250, 150, 150, 80, 100);
        byte[] body = extractInnerBody(pkts.get(0), 16);
        assertEquals("packet[0] is PoolUpdate (1f 01 00 50)",
                "1f 01 00 50", hex(slice(body, 0, 4)));
        assertEquals("delta LE32 = -80 (0xFFFFFFB0)",
                "b0 ff ff ff", hex(slice(body, 4, 4)));
        assertEquals("3 zero padding bytes",
                "00 00 00", hex(slice(body, 8, 3)));
        assertEquals("pool byte = POOL_STA (0x06)",
                0x06, body[11] & 0xff);
        assertEquals("maxSta LE16 = 100 (0x0064)",
                "64 00", hex(slice(body, 12, 2)));
        assertEquals("trailing zeros",
                "00 00", hex(slice(body, 14, 2)));
    }

    @Test
    public void secondPacketIsPoolStatusBroadcastWithStaZero()
            throws Exception {
        // PoolStatusBroadcast: 1f 01 00 30 [HP] [PSI] [STA] [maxHP] [maxHP]
        // After .setsta 0: HP=200, PSI=150 (UNCHANGED), STA=0, maxHP=250 (×2)
        List<byte[]> pkts = captureSetStaZero(
                200, 250, 150, 150, 80, 100);
        byte[] body = extractInnerBody(pkts.get(1), 14);
        assertEquals("packet[1] is PoolStatusBroadcast (1f 01 00 30)",
                "1f 01 00 30", hex(slice(body, 0, 4)));
        assertEquals("HP LE16 = 200 (0x00C8)",
                "c8 00", hex(slice(body, 4, 2)));
        assertEquals("PSI LE16 = 150 (0x0096) — must NOT be zero "
                + "(the symmetric #207 collateral check)",
                "96 00", hex(slice(body, 6, 2)));
        assertEquals("STA LE16 = 0 (after setsta 0)",
                "00 00", hex(slice(body, 8, 2)));
        assertEquals("maxHP LE16 slot 1 = 250 (0x00FA)",
                "fa 00", hex(slice(body, 10, 2)));
        assertEquals("maxHP LE16 slot 2 (repeated) = 250",
                "fa 00", hex(slice(body, 12, 2)));
    }

    @Test
    public void thirdPacketIsCharInfoSyncWithSection2PoolsCorrect()
            throws Exception {
        // CharInfo Section 2 cur/max loop interleave:
        //   HP cur, HP max, PSI cur, PSI max, STA cur, STA max
        // After .setsta 0 with our fixture:
        //   c8 00 fa 00 96 00 96 00 00 00 64 00
        //  (HP=200,maxHP=250,PSI=150,maxPSI=150,STA=0,maxSTA=100)
        List<byte[]> pkts = captureSetStaZero(
                200, 250, 150, 150, 80, 100);
        byte[] dat = pkts.get(2);
        assertNotNull("packet[2] must not be null", dat);

        byte[] expectedPoolRun = {
                (byte) 0xc8, 0x00,   // HP cur = 200
                (byte) 0xfa, 0x00,   // HP max = 250
                (byte) 0x96, 0x00,   // PSI cur = 150 — MUST NOT zero
                (byte) 0x96, 0x00,   // PSI max = 150
                0x00, 0x00,           // STA cur = 0 (after setsta 0)
                0x64, 0x00            // STA max = 100
        };
        assertTrue("CharInfo Section 2 pool block must be the "
                + "interleaved cur/max sequence "
                + "c8 00 fa 00 96 00 96 00 00 00 64 00 "
                + "(HP=200 maxHP=250 PSI=150 maxPSI=150 STA=0 maxSTA=100)\n"
                + "full packet: " + hex(dat),
                containsByteRun(dat, expectedPoolRun));
    }

    @Test
    public void poolUpdateAndStatusAgreeOnStaZeroAndPsiUnchanged()
            throws Exception {
        // Cross-packet consistency: PoolUpdate's STA delta and
        // PoolStatusBroadcast's STA snapshot must agree (both say
        // STA = 0), AND PoolStatusBroadcast's PSI must be UNCHANGED
        // at 150. If either packet zeroes the wrong pool, this
        // test catches it.
        List<byte[]> pkts = captureSetStaZero(
                200, 250, 150, 150, 80, 100);
        byte[] poolUpdateBody = extractInnerBody(pkts.get(0), 16);
        byte[] statusBody    = extractInnerBody(pkts.get(1), 14);

        // PoolUpdate target pool = STA (0x06)
        assertEquals("PoolUpdate targets STA",
                0x06, poolUpdateBody[11] & 0xff);
        // PoolStatusBroadcast STA = 0
        assertEquals("PoolStatus STA cur = 0",
                "00 00", hex(slice(statusBody, 8, 2)));
        // PoolStatusBroadcast PSI = 150 (UNCHANGED — no collateral)
        assertEquals("PoolStatus PSI cur = 150 (unchanged)",
                "96 00", hex(slice(statusBody, 6, 2)));
    }

    // ──────────────────────────────────────── helpers

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
