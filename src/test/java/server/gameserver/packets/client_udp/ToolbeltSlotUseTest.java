package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.ZoneManager;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.WeaponSlotEquip;
import server.interfaces.ServerUDPPacket;
import server.testtools.CapturingUDPConnection;

/**
 * Byte-identity + functional tests for the toolbelt-slot equip /
 * holster interaction (task #195).
 *
 * <h3>Ground truth</h3>
 *
 * <p>The C→S wire is byte-pinned from the user's capture: pressing
 * toolbelt key "1" sent, inside the {@code 0x13} reliable wrapper,
 * exactly {@code 03 11 00 | 1f 00 00 1f 00} — op {@code 0x1f},
 * localId LE16 {@code 0x0000}, tag {@code 0x1f} = {@code SlotUse}
 * ({@code docs/PROTOCOL.md}), slot byte {@code 0x00} (slot 0,
 * 0-based = key "1"). Slot range {@code 0..0x0b} cross-checked vs
 * the client's {@code FUN_007e67b0} "SRV activate slot: %i" handler.
 *
 * <p>The retail capture
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (a
 * sit + NPC scenario) contains no weapon equip, so the S→C reply is
 * built on the established per-entity {@code 0x03/0x1f}
 * state-broadcast shape used by the retail-verified chair-sit
 * ({@code SitOnChair}, tag 0x21): {@code 1f <localId LE2> 1f <slot>}.
 */
public class ToolbeltSlotUseTest {

    /** Install a zone and register the player in it with the given
     *  localId (mirrors SitOnChairTest.installPlazaP1Zone). */
    private static Zone installZone(Player pl, int localId)
            throws Exception {
        Zone z = new Zone(1, "plaza/plaza_p1");
        java.lang.reflect.Field zoneField =
                Player.class.getDeclaredField("currentZone");
        zoneField.setAccessible(true);
        zoneField.set(pl, z);
        java.lang.reflect.Field zlField =
                ZoneManager.class.getDeclaredField("zoneList");
        zlField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.TreeMap<Integer, Zone> zoneList =
                (java.util.TreeMap<Integer, Zone>) zlField.get(null);
        zoneList.put(1, z);
        pl.setMapID(localId);
        java.lang.reflect.Field plField =
                Zone.class.getDeclaredField("playerList");
        plField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.TreeMap<Integer, Player> playerList =
                (java.util.TreeMap<Integer, Player>) plField.get(z);
        playerList.put(localId, pl);
        return z;
    }

    /** The user's exact C→S SlotUse bytes for toolbelt key {@code n}:
     *  {@code 03 <seq2> 1f 00 00 1f <slot>}. With seq=0x0011 and
     *  slot=0 this is the literal capture {@code 03 11 00 1f 00 00
     *  1f 00}. */
    private static byte[] slotUseBody(int slot) {
        return new byte[] {
                0x03, 0x11, 0x00,          // reliable 03 + seq LE2
                0x1f,                      // op 0x1f (GamePackets)
                0x00, 0x00,                // localId LE2 = 0
                0x1f,                      // tag 0x1f = SlotUse
                (byte) (slot & 0xFF)       // toolbelt slot index
        };
    }

    /** Extract the inner game body (post {@code 03 seq2}) from a
     *  reliable {@code 0x13} datagram (offset 10). */
    private static byte[] innerBody(ServerUDPPacket p, int len) {
        DatagramPacket[] dps = p.getDatagramPackets();
        byte[] d = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, d, 0, d.length);
        assertEquals(0x13, d[0] & 0xFF);
        assertEquals(0x03, d[7] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(d, 10, body, 0, len);
        return body;
    }

    /**
     * The C→S decode must consume exactly the user's literal bytes:
     * after skip(7) the reader sits on the slot byte. Slot 0 from
     * {@code 03 11 00 1f 00 00 1f 00} ⇒ equip slot 0.
     */
    @Test
    public void decodesUsersExactBytesAsEquipSlotZero()
            throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installZone(pl, 7);
        CapturingUDPConnection.replaceOn(pl);

        // Sanity: the test's literal sample equals the user's capture.
        assertArrayEquals(
                new byte[] { 0x03, 0x11, 0x00, 0x1f, 0x00, 0x00,
                        0x1f, 0x00 },
                slotUseBody(0));

        assertEquals("no weapon drawn initially",
                -1, pl.getActiveWeaponSlot());
        new ToolbeltSlotUse(slotUseBody(0)).execute(pl);

        assertTrue("pressing a toolbelt key draws that weapon",
                pl.hasWeaponDrawn());
        assertEquals("slot 0 = toolbelt key 1",
                0, pl.getActiveWeaponSlot());
    }

    /**
     * Equipping a slot broadcasts a {@link WeaponSlotEquip} whose
     * inner bytes are {@code 1f <localId LE2> 1f <slot>} (same field
     * order as the retail-verified chair-sit tag 0x21 broadcast).
     */
    @Test
    public void equipBroadcastsPinnedSlotUseBytes() throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installZone(pl, 3);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        new ToolbeltSlotUse(slotUseBody(0)).execute(pl);

        WeaponSlotEquip eq = null;
        for (ServerUDPPacket p : cap.received()) {
            if (p instanceof WeaponSlotEquip) eq = (WeaponSlotEquip) p;
        }
        assertNotNull("equip must emit a WeaponSlotEquip broadcast",
                eq);

        byte[] body = innerBody(eq, 5);
        byte[] expected = {
                0x1f,            // op / per-entity sub-op
                0x03, 0x00,      // localId LE16 = subject mapID 3
                0x1f,            // tag 0x1f = SlotUse
                0x00             // toolbelt slot 0 (drawn)
        };
        assertArrayEquals(
                "WeaponSlotEquip echoes the SlotUse field order",
                expected, body);
    }

    /**
     * Toggle: pressing the already-drawn slot's key holsters it.
     * The holster broadcast carries the {@code 0xff} sentinel.
     */
    @Test
    public void pressingDrawnSlotAgainHolsters() throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installZone(pl, 3);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        new ToolbeltSlotUse(slotUseBody(0)).execute(pl); // draw
        assertEquals(0, pl.getActiveWeaponSlot());

        new ToolbeltSlotUse(slotUseBody(0)).execute(pl); // holster
        assertFalse("re-pressing the drawn slot holsters",
                pl.hasWeaponDrawn());
        assertEquals(-1, pl.getActiveWeaponSlot());

        // Last broadcast must be the holster (slot byte 0xff).
        WeaponSlotEquip last = null;
        for (ServerUDPPacket p : cap.received()) {
            if (p instanceof WeaponSlotEquip) last = (WeaponSlotEquip) p;
        }
        assertNotNull(last);
        byte[] body = innerBody(last, 5);
        assertEquals(0x1f, body[0] & 0xFF);
        assertEquals(0x1f, body[3] & 0xFF);          // tag SlotUse
        assertEquals("holster sentinel", 0xff, body[4] & 0xFF);
        assertEquals(WeaponSlotEquip.HOLSTER, body[4] & 0xFF);
    }

    /**
     * Pressing a different slot's key while a weapon is drawn
     * switches to that weapon (no holster — the new slot is drawn).
     */
    @Test
    public void pressingDifferentSlotSwitchesWeapon() throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installZone(pl, 3);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        new ToolbeltSlotUse(slotUseBody(0)).execute(pl); // draw slot 0
        new ToolbeltSlotUse(slotUseBody(2)).execute(pl); // switch -> 2

        assertTrue(pl.hasWeaponDrawn());
        assertEquals("switched to slot 2",
                2, pl.getActiveWeaponSlot());

        WeaponSlotEquip last = null;
        for (ServerUDPPacket p : cap.received()) {
            if (p instanceof WeaponSlotEquip) last = (WeaponSlotEquip) p;
        }
        assertNotNull(last);
        byte[] body = innerBody(last, 5);
        assertEquals("switch broadcasts the new slot, not holster",
                2, body[4] & 0xFF);
    }

    /** A truncated SlotUse (no slot byte) is ignored, not guessed. */
    @Test
    public void truncatedPacketIsIgnored() throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installZone(pl, 3);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        // 03 <seq2> 1f 00 00 1f  (no slot byte after the tag)
        byte[] trunc = { 0x03, 0x11, 0x00, 0x1f, 0x00, 0x00, 0x1f };
        new ToolbeltSlotUse(trunc).execute(pl);

        assertEquals("truncated packet must not change weapon state",
                -1, pl.getActiveWeaponSlot());
        for (ServerUDPPacket p : cap.received()) {
            assertFalse("truncated SlotUse must not broadcast",
                    p instanceof WeaponSlotEquip);
        }
    }
}
