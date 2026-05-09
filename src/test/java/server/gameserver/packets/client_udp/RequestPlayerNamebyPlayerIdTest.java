package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.PlayerCharacterManager;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Functional test for {@link RequestPlayerNamebyPlayerId} —
 * the C→S {@code 0x03/0x22 sub=0x06 0x00} request for a
 * player's display name given their PlayerId. Handler reads
 * the LE32 PlayerId after a 8-byte skip, looks up the
 * PlayerCharacter, and responds with
 * {@link server.gameserver.packets.server_udp.SendPlayerNamebyPlayerId}.
 */
public class RequestPlayerNamebyPlayerIdTest {

    @Before
    public void initPcManagerWithKnownChar() throws Exception {
        // PlayerCharacterManager.getCharacter requires pcList
        // to be initialised AND contain the looked-up id.
        Field f = PlayerCharacterManager.class.getDeclaredField("pcList");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<PlayerCharacter> list = (List<PlayerCharacter>) f.get(null);
        if (list == null) {
            list = new LinkedList<>();
            f.set(null, list);
        }
        // Ensure the fixture char (MISC_ID = 0x12345678) is in
        // the manager's list. Add a separate test character with
        // MISC_ID = 0xCAFE for lookup.
        PlayerCharacter test = new PlayerCharacter();
        test.setName("Lookup");
        test.setMisc(PlayerCharacter.MISC_ID, 0xCAFE);
        list.add(test);
    }

    /** Build a 12-byte body: skip 8, read LE32 PlayerId. */
    private static byte[] buildBody(int playerId) {
        byte[] b = new byte[12];
        b[0]  = 0x03;
        b[1]  = 0x42;
        b[2]  = 0x00;
        b[3]  = 0x1f;
        b[4]  = 0x05;
        b[5]  = 0x00;
        b[6]  = 0x22;
        b[7]  = 0x06;
        b[8]  = (byte) (playerId        & 0xff);
        b[9]  = (byte) ((playerId >> 8 ) & 0xff);
        b[10] = (byte) ((playerId >> 16) & 0xff);
        b[11] = (byte) ((playerId >> 24) & 0xff);
        return b;
    }

    @Test
    public void executeLooksUpKnownCharacter() {
        Player pl = PacketTestFixture.newPlayer();
        // 0xCAFE is the test character we installed in @Before.
        new RequestPlayerNamebyPlayerId(buildBody(0xCAFE)).execute(pl);
        // Pass = no NPE; the SendPlayerNamebyPlayerId reply was
        // constructed and sent via UDP.
    }

    @Test
    public void executeOnUnknownIdRaisesPredictableException() {
        // PlayerCharacterManager.getCharacter returns null for an
        // unknown id; SendPlayerNamebyPlayerId's constructor then
        // NPEs on .getName(). This test pins the failure mode so
        // a future "null-safe" mutation of the emitter is caught.
        Player pl = PacketTestFixture.newPlayer();
        try {
            new RequestPlayerNamebyPlayerId(buildBody(0xDEAD)).execute(pl);
            fail("expected NullPointerException for unknown id");
        } catch (NullPointerException expected) {
            // Pin: emitter currently NPEs on missing character.
            // A safer "send empty name" or "skip" alternative
            // can be added; this test guards the existing
            // contract until then.
        }
    }

    @Test
    public void playerIdEncodesLittleEndianFromBody() {
        // Defensive: confirm 8-byte skip + LE32 read matches the
        // wire. A garbled byte at offsets 0..7 should not affect
        // lookup.
        Player pl = PacketTestFixture.newPlayer();
        byte[] body = buildBody(0xCAFE);
        for (int i = 0; i < 8; i++) {
            body[i] = (byte) 0xFF;
        }
        new RequestPlayerNamebyPlayerId(body).execute(pl);
        // No exception → 8-byte skip + LE32 read pinned correctly.
    }

    @Test
    public void multipleInvocationsAreIdempotent() {
        // Stateless handler — same input produces the same
        // outcome on each call.
        Player pl = PacketTestFixture.newPlayer();
        for (int i = 0; i < 3; i++) {
            new RequestPlayerNamebyPlayerId(buildBody(0xCAFE)).execute(pl);
        }
        // Pass = no exception.
    }
}
