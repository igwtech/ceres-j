package server.gameserver.command;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.AdminCommandHandler;
import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.command.commands.CommandPack;
import server.gameserver.packets.server_udp.CashUpdate;
import server.gameserver.packets.server_udp.ForcedZoning;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.PoolStatusBroadcast;
import server.gameserver.packets.server_udp.PoolUpdate;
import server.interfaces.ServerUDPPacket;
import server.testtools.CapturingUDPConnection;

/**
 * Task #186 — every state-changing GM command must push the matching
 * S→C HUD-refresh packet, otherwise the server state changes but the
 * in-game HUD (CASH / HLT-STA-PSI pools) stays stale until the next
 * ~1 Hz {@link PoolStatusBroadcast}.
 *
 * <p>Audited path coverage:
 * <ul>
 *   <li><b>givemoney</b> (registry) → {@link CashUpdate} — the
 *       retail-verified wallet carrier
 *       ({@code 0x03/0x1f → 0x25 0x13 → 0x04}, byte-pinned by
 *       {@code CashUpdateByteIdentityTest}).</li>
 *   <li><b>changeskill</b> (registry, alias of legacy {@code !setsub})
 *       → {@link ForcedZoning} — the canonical CharInfo-redelivery
 *       lever so the client recomputes HUD pool maxes from CharInfo
 *       Section 4 (project memory {@code hud_pool_path_confirmed}).</li>
 *   <li><b>.heal</b> / <b>.god</b> (legacy switch) — were the actual
 *       #186 regression: they mutated HP/PSI/STA but emitted NOTHING.
 *       Now push {@link PoolUpdate} signed deltas + a
 *       {@link PoolStatusBroadcast}, same lever {@code .sethp}
 *       already used.</li>
 * </ul>
 */
public class GmCommandHudRefreshTest {

    private GmCommandRegistry reg;

    @Before
    public void setUp() {
        reg = new GmCommandRegistry();
        CommandPack.registerAll(reg);
    }

    /** Fixture player with a capturing UDP connection installed. */
    private static Player gmPlayer(int level, CapturingUDPConnection[] cap) {
        Player pl = PacketTestFixture.newPlayer();
        pl.getAccount().setGmLevel(level);
        cap[0] = CapturingUDPConnection.replaceOn(pl);
        return pl;
    }

    private static boolean anyOfType(List<ServerUDPPacket> pkts,
                                     Class<?> type) {
        return pkts.stream().anyMatch(type::isInstance);
    }

    // ── Registry commands (already wired in #179 — regression guard) ──

    @Test
    public void givemoneyEmitsCashUpdate() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(Account.GM_GAMEMASTER, cap);
        pl.getCharacter().setCash(1000);

        CommandResult r = reg.dispatch(pl, "givemoney 500", s -> {});
        assertTrue("command succeeded", r.isOk());
        assertEquals("cash mutated", 1500, pl.getCharacter().getCash());

        List<ServerUDPPacket> sent = cap[0].received();
        assertTrue("givemoney must emit a CashUpdate HUD refresh "
                + "(before #186 audit this was the only state cmd "
                + "wired; guard against regression)",
                anyOfType(sent, CashUpdate.class));
    }

    @Test
    public void changeskillEmitsForcedZoning() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(Account.GM_GAMEMASTER, cap);

        CommandResult r = reg.dispatch(pl, "changeskill "
                + PlayerCharacter.SUBSKILL_HLT + " 175", s -> {});
        assertTrue(r.isOk());
        assertEquals(175, pl.getCharacter()
                .getSubskillLVL(PlayerCharacter.SUBSKILL_HLT));

        assertTrue("changeskill must ForcedZoning so the client "
                + "re-requests CharInfo and recomputes HUD pools",
                anyOfType(cap[0].received(), ForcedZoning.class));
    }

    // ── Legacy switch commands — the actual #186 fix ─────────────────

    /** Build a player with both a capturing UDP conn (pool packets)
     *  and a capturing TCP conn (the chat reply). */
    private static Player legacyPlayer(CapturingUDPConnection[] cap) {
        Player pl = PacketTestFixture.newPlayer();
        pl.getAccount().setGmLevel(Account.GM_GAMEMASTER);
        cap[0] = CapturingUDPConnection.replaceOn(pl);
        pl.setTcpConnection(new CapturingTCPConnection());
        return pl;
    }

    @Test
    public void healEmitsPoolUpdatesAndStatusBroadcast() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = legacyPlayer(cap);
        PlayerCharacter pc = pl.getCharacter();
        // Deplete every pool so the heal produces non-zero deltas.
        pc.setHealth(1);
        pc.setPsi(1);
        pc.setStamina(1);

        assertTrue(AdminCommandHandler.handle(pl, ".heal"));

        assertEquals("HP restored", pc.getMaxHealth(), pc.getHealth());
        assertEquals("PSI restored", pc.getMaxPsi(), pc.getPsi());
        assertEquals("STA restored", pc.getMaxStamina(),
                pc.getStamina());

        List<ServerUDPPacket> sent = cap[0].received();
        long poolUpdates = sent.stream()
                .filter(PoolUpdate.class::isInstance).count();
        assertEquals("one PoolUpdate per pool (HP/PSI/STA) — before "
                + "#186 .heal emitted ZERO refresh packets",
                3, poolUpdates);
        assertTrue("plus a PoolStatusBroadcast snapshot",
                anyOfType(sent, PoolStatusBroadcast.class));
    }

    @Test
    public void godEmitsHpRefresh() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = legacyPlayer(cap);
        PlayerCharacter pc = pl.getCharacter();
        pc.setHealth(1);

        assertTrue(AdminCommandHandler.handle(pl, ".god"));

        assertEquals(pc.getMaxHealth(), pc.getHealth());
        List<ServerUDPPacket> sent = cap[0].received();
        assertTrue(".god must push a PoolUpdate HP refresh (was "
                + "silent before #186)",
                anyOfType(sent, PoolUpdate.class));
        assertTrue(anyOfType(sent, PoolStatusBroadcast.class));
    }
}
