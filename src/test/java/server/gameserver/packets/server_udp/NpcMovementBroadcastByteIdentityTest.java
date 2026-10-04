package server.gameserver.packets.server_udp;

import static org.junit.Assert.assertEquals;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;

/**
 * Byte layout of {@link NpcMovementBroadcast} against the retail
 * 13-byte mob movement form (harness run fire_bc_v1, industry_p1).
 */
public class NpcMovementBroadcastByteIdentityTest {

    private static byte[] subPacket(NpcMovementBroadcast pkt) {
        DatagramPacket dp = pkt.getDatagramPackets()[0];
        byte[] b = new byte[dp.getLength() - 7];   // strip 0x13 header
        System.arraycopy(dp.getData(), 7, b, 0, b.length);
        return b;
    }

    @Test
    public void matchesRetailSscorpaLayout() {
        // Retail: 20 f803 137a ac7f ca79 7c 000000  (SSCORPA eid 1016)
        // wire Y 0x7a13 / Z 0x7fac / X 0x79ca -> world (-1590, -1517, -83)
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(1016, 9, 415, "SSCORPA",
                0x79ca - 0x8000, 0x7a13 - 0x8000, 0x7fac - 0x8000 + 1,
                0x7c, 100, 0);

        byte[] b = subPacket(new NpcMovementBroadcast(pl, npc));

        String hex = PacketTestFixture.hex(b).replace(" ", "");
        assertEquals("20f803137aac7fca797c000000", hex);
        assertEquals(13, b.length);
    }
}
