package server.gameserver.npc;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for {@link MobState}: every wire byte we have ever
 * observed must round-trip; everything else must report null so the
 * caller can decide whether to log + drop or coerce.
 */
public class MobStateTest {

    @Test
    public void everyKnownByteRoundTrips() {
        for (MobState s : MobState.values()) {
            assertEquals("round-trip failed for " + s,
                    s, MobState.fromWire(s.wireByte()));
        }
    }

    @Test
    public void canonicalByteValuesMatchRetailEvidence() {
        // Frequencies measured against the full corpus — these are
        // the byte values, not arbitrary ordinals.
        assertEquals(0x75, MobState.IDLE.wireByte());
        assertEquals(0x71, MobState.COMBAT.wireByte());
        assertEquals(0x70, MobState.TRANSITION.wireByte());
        assertEquals(0x72, MobState.RARE_72.wireByte());
        assertEquals(0x6f, MobState.RARE_6F.wireByte());
    }

    @Test
    public void unknownBytesReturnNull() {
        assertNull(MobState.fromWire(0x00));
        assertNull(MobState.fromWire(0x73));
        assertNull(MobState.fromWire(0xff));
    }

    @Test
    public void fromWireMasksHighBits() {
        // The decoder receives bytes as ints; ensure the upper 24
        // bits are ignored (e.g. -1 sign-extended from byte 0xff).
        assertEquals(MobState.IDLE,   MobState.fromWire(0xffffff75));
        assertEquals(MobState.COMBAT, MobState.fromWire(0x12345671));
    }
}
