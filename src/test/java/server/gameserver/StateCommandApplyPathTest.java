package server.gameserver;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.packets.server_udp.CashUpdate;
import server.gameserver.packets.server_udp.CharInfo;
import server.gameserver.packets.server_udp.CharsysOnly;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.PoolStatusBroadcast;
import server.gameserver.packets.server_udp.PoolUpdate;
import server.gameserver.packets.server_udp.SoullightUpdate;
import server.interfaces.ServerUDPPacket;
import server.testtools.CapturingUDPConnection;

/**
 * Task #194 — every user-visible state-changing GM command must emit a
 * <b>live-CHARSYS resync</b> ({@link CharInfo} via the Ghidra-pinned
 * {@code 0x03/0x2c} variant-{@code 0x02} single-packet handler) carrying
 * the just-mutated values, so the change repaints the in-game HUD with
 * <b>no zone reload</b>.
 *
 * <p>Wire path pinned in {@code docs/protocol/RE_state_sync.md} +
 * {@code docs/re_state_sync_dump{7..12}.txt}: the {@code 0x03/0x2c}
 * v{@code 0x02} CharInfo packet is the LC message factory
 * ({@code FUN_00840ee0}) {@code case 0x11} (wire sub-type byte
 * {@code 0x12} = {@code LC_RESTORECHAR}, vftable @ {@code 0x00a5d874});
 * its apply slot {@code FUN_00841dc0} → {@code FUN_008033d0} runs
 * {@code FUN_008447d0} (CHARSYS TLV parse) + {@code FUN_0080b8b0} (HUD
 * recompute) — byte-for-byte the same pipeline as FULLCHARSYSTEM UI
 * event {@code 0x6e}. Empirically confirmed by the user: CharInfo
 * redelivery is the only lever that moves the HUD mid-session (observed
 * on zone cross, which re-sends exactly this packet).
 *
 * <p>The verified per-entity carriers ({@link PoolUpdate} /
 * {@link PoolStatusBroadcast}, {@link CashUpdate},
 * {@link SoullightUpdate}) are retained alongside (nearby-observer
 * sync + soullight, which is TCP-only and not in any CHARSYS section).
 *
 * <p>The dead {@code 0x03/0x07} disc-{@code 0x02} multipart
 * ({@link CharsysOnly}, client UI event {@code 0xa8} = no-op QUERY)
 * must NEVER be emitted by any of these commands.
 */
public class StateCommandApplyPathTest {

    private static Player gmPlayer(CapturingUDPConnection[] cap) {
        Player pl = PacketTestFixture.newPlayer();
        pl.getAccount().setGmLevel(Account.GM_GAMEMASTER);
        cap[0] = CapturingUDPConnection.replaceOn(pl);
        pl.setTcpConnection(new CapturingTCPConnection());
        return pl;
    }

    private static boolean anyOfType(List<ServerUDPPacket> p, Class<?> t) {
        return p.stream().anyMatch(t::isInstance);
    }

    private static long countOfType(List<ServerUDPPacket> p, Class<?> t) {
        return p.stream().filter(t::isInstance).count();
    }

    /**
     * Every state command must emit the live-CHARSYS resync — a
     * {@link CharInfo} routed through the pinned single-packet handler.
     */
    private static void assertLiveCharsysResync(List<ServerUDPPacket> p,
            String cmd) {
        assertTrue(cmd + " must emit a live-CHARSYS resync (CharInfo via "
                + "the pinned 0x03/0x2c single-packet handler that runs "
                + "FUN_008447d0 + FUN_0080b8b0 and repaints the HUD with "
                + "no zone reload) — task #194",
                anyOfType(p, CharInfo.class));
    }

    /** No state command may ever emit the dead disc-0x02 CHARSYS path. */
    private static void assertNoDeadCharsysPath(List<ServerUDPPacket> p) {
        assertFalse("a state command emitted the dead 0x03/0x07 "
                + "disc-0x02 CharsysOnly path (client event 0xa8 = "
                + "no-op QUERY, never applies)",
                anyOfType(p, CharsysOnly.class));
    }

    @Test
    public void setHpEmitsLiveCharsysAndPoolUpdate() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);
        PlayerCharacter pc = pl.getCharacter();
        pc.setHealth(pc.getMaxHealth());

        assertTrue(AdminCommandHandler.handle(pl, ".sethp 25"));
        assertEquals(25, pc.getHealth());

        List<ServerUDPPacket> sent = cap[0].received();
        assertLiveCharsysResync(sent, ".sethp");
        assertTrue(".sethp keeps the PoolUpdate carrier for observers",
                anyOfType(sent, PoolUpdate.class));
        assertTrue(anyOfType(sent, PoolStatusBroadcast.class));
        assertNoDeadCharsysPath(sent);
        assertCharInfoCarriesHp(sent, pl, 25);
    }

    @Test
    public void setPsiEmitsLiveCharsys() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);

        assertTrue(AdminCommandHandler.handle(pl, ".setpsi 12"));
        assertEquals(12, pl.getCharacter().getPsi());

        List<ServerUDPPacket> sent = cap[0].received();
        assertLiveCharsysResync(sent, ".setpsi");
        assertTrue(anyOfType(sent, PoolUpdate.class));
        assertNoDeadCharsysPath(sent);
        assertCharInfoCarriesPsi(sent, pl, 12);
    }

    @Test
    public void setStaEmitsLiveCharsys() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);

        assertTrue(AdminCommandHandler.handle(pl, ".setsta 33"));
        assertEquals(33, pl.getCharacter().getStamina());

        List<ServerUDPPacket> sent = cap[0].received();
        assertLiveCharsysResync(sent, ".setsta");
        assertTrue(anyOfType(sent, PoolUpdate.class));
        assertNoDeadCharsysPath(sent);
        assertCharInfoCarriesSta(sent, pl, 33);
    }

    @Test
    public void setSoullightEmitsSoullightUpdateAndResync() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);

        assertTrue(AdminCommandHandler.handle(pl, ".setsl 50"));

        List<ServerUDPPacket> sent = cap[0].received();
        // Soullight is TCP-only (not in any CHARSYS section), so the
        // dedicated carrier remains the actual lever; the resync is
        // still emitted so co-mutated state repaints.
        assertTrue(".setsl must push the verified SoullightUpdate "
                + "carrier (0x02/0x1f → 0x25 0x1f float)",
                anyOfType(sent, SoullightUpdate.class));
        assertLiveCharsysResync(sent, ".setsl");
        assertNoDeadCharsysPath(sent);
    }

    @Test
    public void setSubskillEmitsLiveCharsysNoZoneReload() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);

        assertTrue(AdminCommandHandler.handle(pl,
                "!setsub " + PlayerCharacter.SUBSKILL_HLT + " 175 250"));
        assertEquals(175, pl.getCharacter()
                .getSubskillLVL(PlayerCharacter.SUBSKILL_HLT));

        List<ServerUDPPacket> sent = cap[0].received();
        assertLiveCharsysResync(sent, "!setsub");
        assertNoDeadCharsysPath(sent);
        assertCharInfoCarriesSubskill(sent, pl,
                PlayerCharacter.SUBSKILL_HLT, 175);
    }

    @Test
    public void setMaxHpEmitsLiveCharsys() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);

        assertTrue(AdminCommandHandler.handle(pl, "!setmaxhp 200"));
        assertEquals(200, pl.getCharacter()
                .getSubskillLVL(PlayerCharacter.SUBSKILL_HLT));

        List<ServerUDPPacket> sent = cap[0].received();
        assertLiveCharsysResync(sent, "!setmaxhp");
        assertNoDeadCharsysPath(sent);
        assertCharInfoCarriesSubskill(sent, pl,
                PlayerCharacter.SUBSKILL_HLT, 200);
    }

    @Test
    public void setCashEmitsCashUpdateAndResync() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);

        assertTrue(AdminCommandHandler.handle(pl, ".setcash 123456"));
        assertEquals(123456, pl.getCharacter().getCash());

        List<ServerUDPPacket> sent = cap[0].received();
        assertTrue(".setcash keeps the retail-verified CashUpdate carrier",
                anyOfType(sent, CashUpdate.class));
        assertLiveCharsysResync(sent, ".setcash");
        assertNoDeadCharsysPath(sent);
        assertCharInfoCarriesCash(sent, pl, 123456);
    }

    @Test
    public void healEmitsThreePoolUpdatesAndResync() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);
        PlayerCharacter pc = pl.getCharacter();
        pc.setHealth(1);
        pc.setPsi(1);
        pc.setStamina(1);

        assertTrue(AdminCommandHandler.handle(pl, ".heal"));
        assertEquals(pc.getMaxHealth(), pc.getHealth());

        List<ServerUDPPacket> sent = cap[0].received();
        assertEquals("one PoolUpdate per pool (HP/PSI/STA)",
                3, countOfType(sent, PoolUpdate.class));
        assertTrue(anyOfType(sent, PoolStatusBroadcast.class));
        assertLiveCharsysResync(sent, ".heal");
        assertNoDeadCharsysPath(sent);
    }

    @Test
    public void godEmitsHpRefreshAndResync() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);
        pl.getCharacter().setHealth(1);

        assertTrue(AdminCommandHandler.handle(pl, ".god"));
        assertEquals(pl.getCharacter().getMaxHealth(),
                pl.getCharacter().getHealth());

        List<ServerUDPPacket> sent = cap[0].received();
        assertTrue(anyOfType(sent, PoolUpdate.class));
        assertTrue(anyOfType(sent, PoolStatusBroadcast.class));
        assertLiveCharsysResync(sent, ".god");
        assertNoDeadCharsysPath(sent);
    }

    // ---------- byte-identity: the resync carries the mutated value ----

    private static CharInfo firstCharInfo(List<ServerUDPPacket> p) {
        return (CharInfo) p.stream()
                .filter(CharInfo.class::isInstance)
                .findFirst().orElseThrow(() ->
                        new AssertionError("no CharInfo resync emitted"));
    }

    private static int readShortLE(byte[] a, int off) {
        return (a[off] & 0xff) | ((a[off + 1] & 0xff) << 8);
    }

    private static int readIntLE(byte[] a, int off) {
        return (a[off] & 0xff) | ((a[off + 1] & 0xff) << 8)
                | ((a[off + 2] & 0xff) << 16) | ((a[off + 3] & 0xff) << 24);
    }

    /**
     * Reflectively split the rebuilt CharInfo TLV body into section
     * payloads — the same model {@code CharInfoContentTest} uses.
     */
    private static java.util.Map<Integer, byte[]> sections(CharInfo ci) {
        try {
            ci.newSection(0);
            java.lang.reflect.Field cf = server.networktools
                    .PacketBuilderUDP130307.class
                    .getDeclaredField("complete");
            cf.setAccessible(true);
            byte[] data = ((java.io.ByteArrayOutputStream) cf.get(ci))
                    .toByteArray();
            java.util.Map<Integer, byte[]> out = new java.util.HashMap<>();
            int pos = 3; // skip prelude 0x22 0x02 0x01
            while (pos < data.length) {
                int id = data[pos] & 0xff;
                int len = (data[pos + 1] & 0xff)
                        | ((data[pos + 2] & 0xff) << 8);
                byte[] body = new byte[len];
                System.arraycopy(data, pos + 3, body, 0, len);
                out.put(id, body);
                pos += 3 + len;
            }
            return out;
        } catch (Exception e) {
            throw new AssertionError("section split failed", e);
        }
    }

    // CHARSYS section-2 parser FUN_00845820 (RE_state_sync.md
    // sections 2.3 + 6, task #201):
    //   cur/max pairs:  HP @2/4, PSI @6/8, STA @10/12
    //   trailing HP BUCKET triple @18/20/22 -> charsys+0x3f4/+0x3f8/
    //     +0x3fc. FUN_0080c660 shows current HP as the SUM of these
    //     three, clamped to the regen anchor charsys+0x42c. They are
    //     the current-HP split 0.35/0.45/0.20 (the HP regen tick
    //     FUN_007e87d0 maintains the same invariant) -- NOT pool
    //     ceilings. The earlier #194 "ceiling = max" model is
    //     superseded; emitting max made the HUD sum 3*max and never
    //     reflect .sethp / damage / heal.
    private static final int SEC2_HP_CUR = 2,  SEC2_HP_B0 = 18;
    private static final int SEC2_PSI_CUR = 6, SEC2_HP_B1 = 20;
    private static final int SEC2_STA_CUR = 10, SEC2_HP_B2 = 22;

    private static void assertCharInfoCarriesHp(List<ServerUDPPacket> p,
            Player pl, int expectHp) {
        byte[] sec2 = sections(firstCharInfo(p)).get(2);
        assertNotNull("CharInfo section 2 (pools) must exist", sec2);
        assertEquals("live-CHARSYS resync must carry the mutated HP "
                + "as the section-2 cur field",
                expectHp, readShortLE(sec2, SEC2_HP_CUR));
        // The HUD HP widget (FUN_0080c660) shows the SUM of the
        // three section-2 buckets @18/20/22 (charsys+0x3f4/+0x3f8/
        // +0x3fc). They must sum to the mutated current HP so .sethp
        // / damage / heal actually repaint the HUD (task #201).
        int hpSum = readShortLE(sec2, SEC2_HP_B0)
                + readShortLE(sec2, SEC2_HP_B1)
                + readShortLE(sec2, SEC2_HP_B2);
        org.junit.Assert.assertTrue("section-2 HP bucket sum (" + hpSum
                + ") must equal the mutated current HP (" + expectHp
                + ") -- the .sethp/damage/heal HUD lever, task #201",
                Math.abs(hpSum - expectHp) <= 2);
    }

    private static void assertCharInfoCarriesPsi(List<ServerUDPPacket> p,
            Player pl, int expectPsi) {
        byte[] sec2 = sections(firstCharInfo(p)).get(2);
        assertNotNull("CharInfo section 2 (pools) must exist", sec2);
        assertEquals("live-CHARSYS resync must carry the mutated PSI",
                expectPsi, readShortLE(sec2, SEC2_PSI_CUR));
        // PSI current rides the cur/max loop slot (charsys+0x410);
        // the client parser FUN_00845820 writes only the HP triple to
        // the @18/20/22 buckets, so PSI is asserted via its cur field.
    }

    private static void assertCharInfoCarriesSta(List<ServerUDPPacket> p,
            Player pl, int expectSta) {
        byte[] sec2 = sections(firstCharInfo(p)).get(2);
        assertNotNull("CharInfo section 2 (pools) must exist", sec2);
        assertEquals("live-CHARSYS resync must carry the mutated STA",
                expectSta, readShortLE(sec2, SEC2_STA_CUR));
        // STA current rides the cur/max loop slot (charsys+0x414);
        // FUN_00845820 writes only the HP triple to @18/20/22, so STA
        // is asserted via its cur field.
    }

    private static void assertCharInfoCarriesCash(List<ServerUDPPacket> p,
            Player pl, int expectCash) {
        byte[] sec8 = sections(firstCharInfo(p)).get(8);
        assertNotNull("CharInfo section 8 (wallet) must exist", sec8);
        // Section 8 layout: [0]=0x0a marker, [1..4]=cash LE32.
        assertEquals("live-CHARSYS resync must carry the mutated cash",
                expectCash, readIntLE(sec8, 1));
    }

    private static void assertCharInfoCarriesSubskill(
            List<ServerUDPPacket> p, Player pl, int idx, int expectLvl) {
        byte[] sec4 = sections(firstCharInfo(p)).get(4);
        assertNotNull("CharInfo section 4 (subskills) must exist", sec4);
        // Section 4: 4-byte header + 45 entries × (val u8, rank u8);
        // slot index is 1-based, so entry N is at 4 + (N-1)*2.
        int valOff = 4 + (idx - 1) * 2;
        assertEquals("live-CHARSYS resync must carry the mutated subskill",
                expectLvl & 0xff, sec4[valOff] & 0xff);
    }
}
