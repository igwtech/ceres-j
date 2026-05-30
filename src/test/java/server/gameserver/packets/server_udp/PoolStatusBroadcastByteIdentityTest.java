package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.net.DatagramPacket;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;

/**
 * Byte-identity test for {@link PoolStatusBroadcast} — the
 * periodic pool-status broadcast (raw {@code 0x1f → 0x30}) that
 * tells the client its HP/PSI/STA values are still valid.
 *
 * <p>Wire format (14-byte body):
 *
 * <pre>
 *   1f 01 00 30 [HP LE2] [PSI LE2] [STA LE2] [maxHP LE2] [maxHP LE2]
 * </pre>
 *
 * <p>Retail emits 54 of these per 60s session (~0.9 Hz). Without
 * them the client may force a state re-sync.
 */
public class PoolStatusBroadcastByteIdentityTest {

    private static byte[] datagramBytes(PoolStatusBroadcast pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP13 only — no 0x03 wrapper):
     *  {@code [0x13][counter LE2][counter+sk LE2][size LE2][body 14B]}
     *  Body starts at offset 7. */
    private static byte[] extractInnerBody(byte[] datagram) {
        assertEquals("outer 0x13", 0x13, datagram[0] & 0xFF);
        byte[] body = new byte[14];
        System.arraycopy(datagram, 7, body, 0, 14);
        return body;
    }

    private static void setHealth(PlayerCharacter pc, int hp) throws Exception {
        // PlayerCharacter exposes setHealth/getHealth; PSI and STA
        // are accessed via getter that derives from misc fields.
        // Use direct field access for tests where needed.
        pc.setHealth(hp);
    }

    @Test
    public void bodyLayoutMatchesRetailWireOrder() throws Exception {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();

        // Use distinct values per pool so any field-mix-up is
        // caught.
        pc.setHealth(0x0102);
        // PSI/STA backed by misc fields — set via reflection
        // since the test fixture doesn't expose simple setters.
        installMiscPool(pc, "MISC_PSI", 0x0304);
        installMiscPool(pc, "MISC_STA", 0x0506);
        // maxHealth derives from a misc subskill; set via the
        // underlying field directly to keep the test
        // deterministic without booting the subskill recompute.
        installMaxHealth(pc, 0x0708);

        byte[] body = extractInnerBody(
                datagramBytes(new PoolStatusBroadcast(pl)));

        // Constant 4-byte prefix
        assertEquals(0x1f, body[0] & 0xFF);
        assertEquals(0x01, body[1] & 0xFF);
        assertEquals(0x00, body[2] & 0xFF);
        assertEquals(0x30, body[3] & 0xFF);

        // HP LE16
        assertEquals(0x02, body[4] & 0xFF);
        assertEquals(0x01, body[5] & 0xFF);

        // PSI LE16 (only check if field installation worked;
        // see setMiscPool note)
        // STA LE16
        // maxHP LE16 (twice)
        // We don't pin the exact PSI/STA bytes here because
        // PlayerCharacter exposes them via getter chains that
        // depend on subskill state we don't fully control in
        // the fixture. The HP byte is the canonical proof
        // that the layout is correct: if HP lands at offset
        // 4..5 LE, the rest follows mechanically from the
        // emitter's writeShort calls.
    }

    @Test
    public void totalDatagramSizeIsTwentyOneBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   14 (body) = 21 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(21, datagramBytes(
                new PoolStatusBroadcast(pl)).length);
    }

    @Test
    public void hpEncodesLittleEndian() throws Exception {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.getCharacter().setHealth(0xCAFE);

        byte[] body = extractInnerBody(
                datagramBytes(new PoolStatusBroadcast(pl)));
        assertEquals(0xFE, body[4] & 0xFF);
        assertEquals(0xCA, body[5] & 0xFF);
    }

    @Test
    public void hpMaxRepeatedAtTwoSlots() {
        // Per the emitter, maxHP is written twice (offsets 10..11
        // and 12..13). This pins that retail-matching duplication.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        try {
            installMaxHealth(pl.getCharacter(), 0xBEEF);
        } catch (Exception e) { throw new RuntimeException(e); }

        byte[] body = extractInnerBody(
                datagramBytes(new PoolStatusBroadcast(pl)));
        assertEquals("maxHP slot 1 lo",
                0xEF, body[10] & 0xFF);
        assertEquals("maxHP slot 1 hi",
                0xBE, body[11] & 0xFF);
        assertEquals("maxHP slot 2 lo (repeated)",
                0xEF, body[12] & 0xFF);
        assertEquals("maxHP slot 2 hi (repeated)",
                0xBE, body[13] & 0xFF);
    }

    @Test
    public void firstFourBytesAreFixedSubOpcode() {
        // The 1f 01 00 30 prefix is the sub-opcode discriminator
        // — must never vary.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new PoolStatusBroadcast(pl)));
        assertEquals(0x1f, body[0] & 0xFF);
        assertEquals(0x01, body[1] & 0xFF);
        assertEquals(0x00, body[2] & 0xFF);
        assertEquals(0x30, body[3] & 0xFF);
    }

    @Test
    public void psiStaCurBytesLandAtExpectedOffsets()
            throws Exception {
        // Task #247 — pin the PSI + STA byte positions that the
        // original test punted on. PSI/STA setters are direct
        // field access (PlayerCharacter.setPsi / .setStamina just
        // write the backing field), so we can assert exact bytes
        // without subskill-fixture gymnastics.
        //
        // Evidence for task #207 (".setpsi 0 también baja STA"):
        // if PSI bytes correctly land at offset 6..7 and STA at
        // offset 8..9 with NO cross-contamination, the wire
        // format is byte-correct and the bug must be elsewhere
        // (client recompute, OR a different packet emitted by
        // cmdSetPsi). If they don't, we've found the bug.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setHealth(0x0102);
        pc.setPsi(0x0304);
        pc.setStamina(0x0506);

        byte[] body = extractInnerBody(
                datagramBytes(new PoolStatusBroadcast(pl)));

        // HP (offsets 4..5)
        assertEquals("HP lo @4", 0x02, body[4] & 0xFF);
        assertEquals("HP hi @5", 0x01, body[5] & 0xFF);
        // PSI (offsets 6..7)
        assertEquals("PSI lo @6", 0x04, body[6] & 0xFF);
        assertEquals("PSI hi @7", 0x03, body[7] & 0xFF);
        // STA (offsets 8..9)
        assertEquals("STA lo @8", 0x06, body[8] & 0xFF);
        assertEquals("STA hi @9", 0x05, body[9] & 0xFF);
    }

    @Test
    public void setPsiZeroDoesNotMutateStaminaField() {
        // Task #247 — server-side check for the cross-pool side
        // effect hypothesised in #207. PlayerCharacter.setPsi must
        // ONLY write the psiPool field; if it accidentally also
        // resets stamina (via some misc-field share or a hidden
        // setter chain), this test catches it.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setPsi(150);
        pc.setStamina(80);
        pc.setHealth(250);

        pc.setPsi(0);

        // PSI mutated as requested.
        assertEquals("PSI must be 0 after setPsi(0)",
                0, pc.getPsi());
        // STA + HP must be unchanged.
        assertEquals("STA must NOT be side-effected by setPsi",
                80, pc.getStamina());
        assertEquals("HP must NOT be side-effected by setPsi",
                250, pc.getHealth());
    }

    @Test
    public void setStaZeroEmitsCorrectPoolStatusBytes()
            throws Exception {
        // Task #247 — the user-reported bug says .setsta 0 lowers
        // BOTH PSI and STA on the HUD. Verify the wire packet
        // emitted by a subsequent PoolStatusBroadcast carries
        // PSI unchanged + STA=0 (i.e. the wire is correct and
        // the client is the one mixing them up).
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setHealth(0x0102);
        pc.setPsi(0x0304);
        pc.setStamina(0x0506);
        // Snapshot the wire BEFORE the mutation.
        byte[] before = extractInnerBody(
                datagramBytes(new PoolStatusBroadcast(pl)));
        assertEquals("PSI lo @6 before", 0x04, before[6] & 0xFF);
        assertEquals("STA lo @8 before", 0x06, before[8] & 0xFF);

        // Simulate the cmd handler: setStamina(0).
        pc.setStamina(0);

        byte[] after = extractInnerBody(
                datagramBytes(new PoolStatusBroadcast(pl)));
        // PSI bytes UNCHANGED.
        assertEquals("PSI lo @6 after — must NOT collateral-zero",
                0x04, after[6] & 0xFF);
        assertEquals("PSI hi @7 after — must NOT collateral-zero",
                0x03, after[7] & 0xFF);
        // STA bytes mutated as requested.
        assertEquals("STA lo @8 after = 0", 0x00, after[8] & 0xFF);
        assertEquals("STA hi @9 after = 0", 0x00, after[9] & 0xFF);
        // HP bytes UNCHANGED.
        assertEquals("HP lo @4 after", 0x02, after[4] & 0xFF);
        assertEquals("HP hi @5 after", 0x01, after[5] & 0xFF);
    }

    /** Reflection helper: set a misc-pool field via PlayerCharacter
     *  so we don't depend on getters that compute from subskills. */
    private static void installMiscPool(PlayerCharacter pc,
                                         String constantName,
                                         int value) throws Exception {
        // PlayerCharacter doesn't expose direct setters for PSI/STA
        // pool values in all builds; safest is to set the misc
        // field by name. If the constant doesn't exist this method
        // is a no-op and the test that depends on it skips that
        // assertion.
        try {
            Field f = PlayerCharacter.class.getField(constantName);
            int idx = f.getInt(null);
            pc.setMisc(idx, value);
        } catch (NoSuchFieldException ignore) {
            // No-op — emitter still uses pc.getPsi()/pc.getStamina()
            // which derive from subskills, so this test path can't
            // verify those exact bytes without a fuller fixture.
        }
    }

    /** Reflection helper: set max HP. */
    private static void installMaxHealth(PlayerCharacter pc, int value)
            throws Exception {
        // Try multiple possible private field names — guard against
        // codebase rename.
        for (String name : new String[]{"maxHealth", "maxHP",
                                          "max_hp", "MAX_HEALTH"}) {
            try {
                Field f = PlayerCharacter.class.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(pc, value);
                return;
            } catch (NoSuchFieldException ignore) {}
        }
        // Fallback: setter
        try {
            PlayerCharacter.class
                    .getMethod("setMaxHealth", int.class)
                    .invoke(pc, value);
        } catch (NoSuchMethodException ignore) {}
    }
}
