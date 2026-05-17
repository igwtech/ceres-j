package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import org.junit.Test;

import server.database.accounts.Account;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketReaderUDP;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.GameServerEvent;

/**
 * Tests the native GM-command UDP path
 * {@code 0x03/0x1f/<localId>/0x06}:
 * <ul>
 *   <li>structured parse (fixed offsets, not a buffer scan);</li>
 *   <li>admin gating — a non-GM session cannot toggle noclip;</li>
 *   <li>a GM session does toggle;</li>
 *   <li>the UDP reader routes the path to {@link
 *       AdminCommandRequest}.</li>
 * </ul>
 */
public class AdminCommandRequestTest {

    /** [0x03][seq LE2][0x1f][localId LE2][0x06][value] */
    private static byte[] gmNoclipPacket(int value) {
        return new byte[] {
            0x03, 0x11, 0x00,        // wrapper + seq
            0x1f, 0x07, 0x00,        // 0x1f + localId
            0x06,                    // GM-command tag
            (byte) value             // argument byte
        };
    }

    @Test
    public void nonGmIsDeniedAndNoclipStaysOff() {
        Player pl = PacketTestFixture.newPlayer();
        pl.getAccount().setGmLevel(Account.GM_PLAYER);
        assertFalse(pl.isNoclip());

        new AdminCommandRequest(gmNoclipPacket(1)).execute(pl);

        assertFalse("non-GM must not toggle noclip via UDP",
                pl.isNoclip());
    }

    @Test
    public void moderatorIsAlsoDenied() {
        Player pl = PacketTestFixture.newPlayer();
        pl.getAccount().setGmLevel(Account.GM_MODERATOR);
        new AdminCommandRequest(gmNoclipPacket(1)).execute(pl);
        assertFalse(pl.isNoclip());
    }

    @Test
    public void gameMasterTogglesNoclipOn() {
        Player pl = PacketTestFixture.newPlayer();
        pl.getAccount().setGmLevel(Account.GM_GAMEMASTER);

        new AdminCommandRequest(gmNoclipPacket(1)).execute(pl);
        assertTrue("GM enables noclip via UDP value=1",
                pl.isNoclip());

        new AdminCommandRequest(gmNoclipPacket(0)).execute(pl);
        assertFalse("GM disables noclip via UDP value=0",
                pl.isNoclip());
    }

    @Test
    public void malformedPacketIsIgnoredNotApplied() {
        Player pl = PacketTestFixture.newPlayer();
        pl.getAccount().setGmLevel(Account.GM_ADMIN);
        // tag byte at offset 6 is 0x99, not 0x06 → structured parse
        // rejects it (a naive scan-for-0x06 would still false-match
        // the 0x06 we plant in the payload at offset 7).
        byte[] bad = new byte[] {
            0x03, 0x11, 0x00, 0x1f, 0x07, 0x00,
            (byte) 0x99, 0x06
        };
        new AdminCommandRequest(bad).execute(pl);
        assertFalse(pl.isNoclip());
    }

    @Test
    public void readerRoutesTheGmPathToAdminCommandRequest()
            throws Exception {
        Method m = GamePacketReaderUDP.class.getDeclaredMethod(
                "decodesub13", byte[].class);
        m.setAccessible(true);
        GameServerEvent ev = (GameServerEvent) m.invoke(
                null, (Object) gmNoclipPacket(1));
        assertNotNull(ev);
        assertTrue("0x03/0x1f/<localId>/0x06 must route to "
                        + "AdminCommandRequest, got "
                        + ev.getClass().getName(),
                ev instanceof AdminCommandRequest);
    }
}
