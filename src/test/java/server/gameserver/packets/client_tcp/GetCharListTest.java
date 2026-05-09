package server.gameserver.packets.client_tcp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.PlayerCharacterManager;
import server.gameserver.CapturingTCPConnection;
import server.gameserver.packets.server_tcp.CharList;
import server.interfaces.ServerTCPPacket;

/**
 * Functional test for {@link GetCharList} — the C→S
 * {@code 0x84 0x82} sub-op that asks the server for the
 * account's 4 character slots. The handler responds with
 * {@link CharList}.
 *
 * <p>Pinning so a refactor of the character-lifecycle code
 * (Phase 1 of the implementation plan) can't accidentally
 * change which packet flies on the wire.
 */
public class GetCharListTest {

    @Before
    public void initPcManager() throws Exception {
        // CharList → PlayerCharacterManager.getCharacter requires
        // pcList to be initialised; install an empty list.
        Field f = PlayerCharacterManager.class.getDeclaredField("pcList");
        f.setAccessible(true);
        if (f.get(null) == null) {
            f.set(null, new LinkedList<PlayerCharacter>());
        }
    }

    @Test
    public void executeEmitsCharListResponse() {
        CapturingTCPConnection cap = new CapturingTCPConnection();
        Account acc = new Account(0);
        acc.setUsername("tester");
        cap.setAccount(acc);

        new GetCharList(new byte[]{(byte) 0x84, (byte) 0x82,
                0x00, 0x00, 0x00, 0x00}).execute(cap);

        List<ServerTCPPacket> sent = cap.received();
        assertEquals("expected exactly one TCP response",
                1, sent.size());
        assertTrue("response must be CharList, got "
                + sent.get(0).getClass().getName(),
                sent.get(0) instanceof CharList);
    }

    @Test
    public void responseHasOpcode8385() {
        CapturingTCPConnection cap = new CapturingTCPConnection();
        Account acc = new Account(42);
        acc.setUsername("u");
        cap.setAccount(acc);

        new GetCharList(new byte[6]).execute(cap);

        ServerTCPPacket resp = cap.received().get(0);
        byte[] data = resp.getData();
        // FE-frame at [0..2], opcode 83 85 at [3..4]
        assertEquals((byte) 0xfe, data[0]);
        assertEquals((byte) 0x83, data[3]);
        assertEquals((byte) 0x85, data[4]);
    }

    @Test
    public void emptyAccountProducesFourDummySlots() {
        // Account with no characters → CharList response has
        // 4 dummy slot records (per CharList byte test).
        CapturingTCPConnection cap = new CapturingTCPConnection();
        Account acc = new Account(0);
        acc.setUsername("empty");
        cap.setAccount(acc);

        new GetCharList(new byte[6]).execute(cap);

        ServerTCPPacket resp = cap.received().get(0);
        // body length = 8B header + 4 × 41B dummies = 172
        // (matches CharListByteIdentityTest's pin)
        int bodyLen = (resp.getData()[1] & 0xFF)
                | ((resp.getData()[2] & 0xFF) << 8);
        assertEquals(172, bodyLen);
    }

    @Test
    public void multipleInvocationsAreIndependent() {
        // Calling GetCharList twice produces two CharList
        // responses (the handler is stateless).
        CapturingTCPConnection cap = new CapturingTCPConnection();
        Account acc = new Account(1);
        acc.setUsername("multi");
        cap.setAccount(acc);

        new GetCharList(new byte[6]).execute(cap);
        new GetCharList(new byte[6]).execute(cap);

        assertEquals(2, cap.received().size());
        assertTrue(cap.received().get(0) instanceof CharList);
        assertTrue(cap.received().get(1) instanceof CharList);
    }
}
