package server.database.playerCharacters;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

/**
 * Pure-unit tests for the {@link PlayerCharacter#getUuid()} / {@link PlayerCharacter#setUuid(UUID)}
 * accessors added alongside the schema v5 migration. The integer
 * {@code MISC_ID} field stays the wire-protocol identity (CharInfo,
 * 0x1b position broadcasts, etc.); the UUID is purely for SOAP API
 * integrations.
 */
public class PlayerCharacterUuidTest {

    @Test
    public void newCharacterStartsWithoutUuid() {
        PlayerCharacter pc = new PlayerCharacter();
        assertNull("Freshly constructed characters should have no UUID "
                + "until createCharacter() or DB load assigns one",
                pc.getUuid());
    }

    @Test
    public void setUuidRoundTripsThroughGetUuid() {
        PlayerCharacter pc = new PlayerCharacter();
        UUID expected = UUID.randomUUID();
        pc.setUuid(expected);
        assertEquals(expected, pc.getUuid());
    }

    @Test
    public void setUuidAcceptsNullForExplicitClear() {
        PlayerCharacter pc = new PlayerCharacter();
        pc.setUuid(UUID.randomUUID());
        assertNotNull(pc.getUuid());
        pc.setUuid(null);
        assertNull(pc.getUuid());
    }

    @Test
    public void uuidIsIndependentOfMiscId() {
        // MISC_ID is the wire-protocol identity broadcast in CharInfo and
        // entity ID packets. The UUID lives separately and must be
        // settable without touching MISC_ID.
        PlayerCharacter pc = new PlayerCharacter();
        pc.setMisc(PlayerCharacter.MISC_ID, 12345);
        UUID u = UUID.randomUUID();
        pc.setUuid(u);
        assertEquals(12345, pc.getMisc(PlayerCharacter.MISC_ID));
        assertEquals(u, pc.getUuid());
    }

    @Test
    public void distinctCharactersCanHoldDistinctUuids() {
        PlayerCharacter a = new PlayerCharacter();
        PlayerCharacter b = new PlayerCharacter();
        a.setUuid(UUID.randomUUID());
        b.setUuid(UUID.randomUUID());
        assertNotEquals(a.getUuid(), b.getUuid());
    }
}
