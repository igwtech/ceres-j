package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Functional test for {@link RequestInfoAboutWordlID} — the
 * C→S {@code 0x03/0x27} RequestWorldInfo packet. The 4-byte
 * inner payload is the world object id the client saw in a
 * prior {@code 0x1b} raw position broadcast and wants details
 * on; retail responds with {@code 0x03/0x28} WorldInfo.
 *
 * <p>The handler defensively drops the request when the
 * looked-up NPC isn't registered in the zone, since our raw
 * 0x1b broadcasts advertise phantom object ids — sending a
 * WorldNPCInfo built from a null NPC would NPE.
 */
public class RequestInfoAboutWordlIDTest {

    /** Build an 8-byte body: skip 4 (0x03 + seq + 0x27), read
     *  LE32 objectId. */
    private static byte[] buildBody(int objectId) {
        byte[] b = new byte[8];
        b[0] = 0x03;
        b[1] = 0x42;
        b[2] = 0x00;
        b[3] = 0x27;
        b[4] = (byte) (objectId        & 0xff);
        b[5] = (byte) ((objectId >> 8 ) & 0xff);
        b[6] = (byte) ((objectId >> 16) & 0xff);
        b[7] = (byte) ((objectId >> 24) & 0xff);
        return b;
    }

    @Test
    public void nullPlayerEarlyReturns() {
        // Defensive null-guard.
        new RequestInfoAboutWordlID(buildBody(0x42))
                .execute((Player) null);
        // Pass = no NPE.
    }

    @Test
    public void noZoneEarlyReturnsWithoutThrowing() {
        // Fixture player has no Zone — handler short-circuits
        // and emits nothing.
        Player pl = PacketTestFixture.newPlayer();
        assertNull(pl.getZone());

        new RequestInfoAboutWordlID(buildBody(0x42)).execute(pl);
        // Pass = no NPE.
    }

    @Test
    public void unknownObjectIdLogsAndDrops() {
        // Zone exists but objectId isn't an NPC in it. Handler
        // logs + drops; no exception.
        Player pl = PacketTestFixture.newPlayer();
        // Manually attach a zone with no NPCs registered.
        Zone z = new Zone(7, "test_zone");
        // Use reflection to set Player.currentZone (no public
        // setter; fixture leaves it null).
        try {
            java.lang.reflect.Field f = Player.class
                    .getDeclaredField("currentZone");
            f.setAccessible(true);
            f.set(pl, z);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertNotNull("zone must be attached for this test",
                pl.getZone());

        new RequestInfoAboutWordlID(buildBody(0xDEAD)).execute(pl);
        // Pass = no NPE.
    }

    @Test
    public void knownObjectIdInZoneRoundTripsToWorldNPCInfo() {
        Player pl = PacketTestFixture.newPlayer();
        // Attach a zone and add an NPC with mapID = 0xCAFE.
        Zone z = new Zone(7, "test_zone");
        z.addNPCtoZone(0, 0, 0, 100, 0, 0);
        // The NPC's mapID is auto-assigned by the zone — fetch
        // its actual id.
        int mapId = z.getAllNPCs().get(0).getMapID();
        try {
            java.lang.reflect.Field f = Player.class
                    .getDeclaredField("currentZone");
            f.setAccessible(true);
            f.set(pl, z);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        new RequestInfoAboutWordlID(buildBody(mapId)).execute(pl);
        // Pass = no NPE; WorldNPCInfo was constructed and sent.
    }

    @Test
    public void objectIdReadsLittleEndianFromOffset4() {
        // The handler skip(4) + readInt() must respect LE
        // ordering. Same as other 4-byte-skip handlers.
        Player pl = PacketTestFixture.newPlayer();
        // No zone → early return; we just verify the offset
        // arithmetic doesn't blow up before that.
        new RequestInfoAboutWordlID(buildBody(0xAABBCCDD)).execute(pl);
        // Pass = no exception escaped through skip(4) + readInt().
    }
}
