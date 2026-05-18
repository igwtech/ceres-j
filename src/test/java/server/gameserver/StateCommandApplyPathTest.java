package server.gameserver;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.packets.server_udp.CashUpdate;
import server.gameserver.packets.server_udp.CharsysOnly;
import server.gameserver.packets.server_udp.ForcedZoning;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.PoolStatusBroadcast;
import server.gameserver.packets.server_udp.PoolUpdate;
import server.gameserver.packets.server_udp.SoullightUpdate;
import server.interfaces.ServerUDPPacket;
import server.testtools.CapturingUDPConnection;

/**
 * Task #191 — every user-visible state-changing GM command must
 * exercise a client-<em>applying</em> S→C path so the mutation actually
 * reflects on the in-game HUD.
 *
 * <p>The applying paths (no single-packet {@code 0x6e} CHARSYS opcode is
 * pinned in any available spec; case {@code 0xb3} is dead — project
 * memory {@code charsys_dead_code} — so the proven verified-carrier /
 * {@link ForcedZoning} fallbacks endorsed by project memory
 * {@code hud_pool_path_confirmed} and {@code cash_and_falldamage_subops}
 * are the levers):
 * <ul>
 *   <li>HP/PSI/STA → {@link PoolUpdate} + {@link PoolStatusBroadcast}</li>
 *   <li>Soullight → {@link SoullightUpdate}</li>
 *   <li>Subskill / max-HP → {@link ForcedZoning} CharInfo redelivery</li>
 *   <li>Cash → {@link CashUpdate}</li>
 * </ul>
 *
 * <p>The dead {@code 0x03/0x07} disc-{@code 0x02} multipart
 * ({@link CharsysOnly}, client UI event {@code 0xa8} = no-op QUERY) must
 * NEVER be emitted by any of these commands — that was the root cause of
 * GM commands not reaching the client.
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

    /** No state command may ever emit the dead disc-0x02 CHARSYS path. */
    private static void assertNoDeadCharsysPath(List<ServerUDPPacket> p) {
        assertFalse("a state command emitted the dead 0x03/0x07 "
                + "disc-0x02 CharsysOnly path (client event 0xa8 = "
                + "no-op QUERY, never applies) — task #191 regression",
                anyOfType(p, CharsysOnly.class));
    }

    @Test
    public void setHpEmitsPoolUpdateNotDeadCharsys() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);
        PlayerCharacter pc = pl.getCharacter();
        pc.setHealth(pc.getMaxHealth());

        assertTrue(AdminCommandHandler.handle(pl, ".sethp 25"));
        assertEquals(25, pc.getHealth());

        List<ServerUDPPacket> sent = cap[0].received();
        assertTrue(".sethp must push a PoolUpdate (retail-verified "
                + "0x1f 01 00 50 signed delta)",
                anyOfType(sent, PoolUpdate.class));
        assertTrue(anyOfType(sent, PoolStatusBroadcast.class));
        assertNoDeadCharsysPath(sent);
    }

    @Test
    public void setPsiEmitsPoolUpdate() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);

        assertTrue(AdminCommandHandler.handle(pl, ".setpsi 12"));
        assertEquals(12, pl.getCharacter().getPsi());

        List<ServerUDPPacket> sent = cap[0].received();
        assertTrue(anyOfType(sent, PoolUpdate.class));
        assertTrue(anyOfType(sent, PoolStatusBroadcast.class));
        assertNoDeadCharsysPath(sent);
    }

    @Test
    public void setStaEmitsPoolUpdate() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);

        assertTrue(AdminCommandHandler.handle(pl, ".setsta 33"));
        assertEquals(33, pl.getCharacter().getStamina());

        List<ServerUDPPacket> sent = cap[0].received();
        assertTrue(anyOfType(sent, PoolUpdate.class));
        assertTrue(anyOfType(sent, PoolStatusBroadcast.class));
        assertNoDeadCharsysPath(sent);
    }

    @Test
    public void setSoullightEmitsSoullightUpdate() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);

        assertTrue(AdminCommandHandler.handle(pl, ".setsl 50"));

        List<ServerUDPPacket> sent = cap[0].received();
        assertTrue(".setsl must push the verified SoullightUpdate "
                + "carrier (0x02/0x1f → 0x25 0x1f float)",
                anyOfType(sent, SoullightUpdate.class));
        assertNoDeadCharsysPath(sent);
    }

    @Test
    public void setSubskillEmitsForcedZoning() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);

        assertTrue(AdminCommandHandler.handle(pl,
                "!setsub " + PlayerCharacter.SUBSKILL_HLT + " 175 250"));
        assertEquals(175, pl.getCharacter()
                .getSubskillLVL(PlayerCharacter.SUBSKILL_HLT));

        List<ServerUDPPacket> sent = cap[0].received();
        assertTrue("!setsub must ForcedZoning so the client "
                + "re-reads a full CharInfo and recomputes HUD pools "
                + "(project memory hud_pool_path_confirmed)",
                anyOfType(sent, ForcedZoning.class));
        assertNoDeadCharsysPath(sent);
    }

    @Test
    public void setMaxHpEmitsForcedZoning() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);

        assertTrue(AdminCommandHandler.handle(pl, "!setmaxhp 200"));
        assertEquals(200, pl.getCharacter()
                .getSubskillLVL(PlayerCharacter.SUBSKILL_HLT));

        List<ServerUDPPacket> sent = cap[0].received();
        assertTrue(anyOfType(sent, ForcedZoning.class));
        assertNoDeadCharsysPath(sent);
    }

    @Test
    public void setCashEmitsCashUpdate() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);

        assertTrue(AdminCommandHandler.handle(pl, ".setcash 123456"));
        assertEquals(123456, pl.getCharacter().getCash());

        List<ServerUDPPacket> sent = cap[0].received();
        assertTrue(".setcash must push the retail-verified CashUpdate "
                + "carrier (0x03/0x1f → 0x25 0x13 → 0x04)",
                anyOfType(sent, CashUpdate.class));
        assertNoDeadCharsysPath(sent);
    }

    @Test
    public void healEmitsThreePoolUpdates() {
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
        assertNoDeadCharsysPath(sent);
    }

    @Test
    public void godEmitsHpRefresh() {
        CapturingUDPConnection[] cap = new CapturingUDPConnection[1];
        Player pl = gmPlayer(cap);
        pl.getCharacter().setHealth(1);

        assertTrue(AdminCommandHandler.handle(pl, ".god"));
        assertEquals(pl.getCharacter().getMaxHealth(),
                pl.getCharacter().getHealth());

        List<ServerUDPPacket> sent = cap[0].received();
        assertTrue(anyOfType(sent, PoolUpdate.class));
        assertTrue(anyOfType(sent, PoolStatusBroadcast.class));
        assertNoDeadCharsysPath(sent);
    }
}
