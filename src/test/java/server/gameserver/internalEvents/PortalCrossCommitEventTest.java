package server.gameserver.internalEvents;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.SqliteDatabase;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.PortalResolver;
import server.gameserver.Zone;
import server.gameserver.ZoneManager;
import server.gameserver.packets.server_tcp.InteractionAck;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_udp.ChangeLocation;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.state.ClientState;
import server.interfaces.ServerTCPPacket;
import server.interfaces.ServerUDPPacket;
import server.testtools.CapturingUDPConnection;

/**
 * Functional test for {@link PortalCrossCommitEvent} — the deferred
 * portal-cross commit fired ~470ms after a {@code UseItem} click on
 * a world-change actor. Validates the retail-faithful timing fix
 * for task #172 (in-session client transition).
 *
 * <p>The event must (when fired):
 * <ul>
 *   <li>commit {@code MISC_LOCATION} to the destination zone</li>
 *   <li>call {@code pl.updateZone()}</li>
 *   <li>persist via {@code PlayerCharacterManager.saveCharacter}</li>
 *   <li>emit TCP {@code 0x83/0x0c Location}</li>
 *   <li>emit UDP {@code 0x03/0x1f/0x38 ChangeLocation}</li>
 *   <li>emit TCP {@code 0xa0/0x02 InteractionAck} pair</li>
 * </ul>
 *
 * <p>Graceful-no-op contract when the player has disconnected
 * (UDP connection torn down) during the 470ms window — the commit
 * is dropped (preferable to leaving a half-applied state).
 */
public class PortalCrossCommitEventTest {

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        SqliteDatabase.initWithConnection(conn);
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO player_characters "
                    + "(id, name, location, uuid) VALUES "
                    + "(0, 'TestChar', 7,"
                    + " '00000000-0000-0000-0000-000000000172')");
        }
        // Seed two zones — source (7) + destination (946).
        java.lang.reflect.Field zlField =
                ZoneManager.class.getDeclaredField("zoneList");
        zlField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.TreeMap<Integer, Zone> zoneList =
                (java.util.TreeMap<Integer, Zone>) zlField.get(null);
        zoneList.put(7,   new Zone(7,   "pepper/pepper_p3"));
        zoneList.put(946, new Zone(946, "citysewer/peppersewer"));
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null) conn.close();
        // Clear the static WorldManager.weList so this test's
        // seedWorldManager() entries don't bleed into the next
        // test class (SitOnChairTest etc. resolve world paths via
        // WorldManager.getWorldname and assume an empty map).
        clearWorldManagerWeList();
    }

    private static void clearWorldManagerWeList() throws Exception {
        java.lang.reflect.Field f =
                server.database.worlds.WorldManager.class
                        .getDeclaredField("weList");
        f.setAccessible(true);
        ((java.util.TreeMap<?, ?>) f.get(null)).clear();
    }

    private static PortalResolver.Portal buildPortal(int dest)
            throws Exception {
        // Mirror the §5 retail ground-truth: pepper_p3 → sewer 946
        // via worldmodel 380 / appplaces 130 (entityType=0 since
        // ft=18 isn't in {20,29}). Portal ctor is package-private;
        // reach it via reflection.
        java.lang.reflect.Constructor<PortalResolver.Portal> ctor =
                PortalResolver.Portal.class.getDeclaredConstructor(
                    int.class, int.class, int.class,
                    int.class, int.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(380, 18, 130, dest, 1, 4);
    }

    @Test
    public void executeCommitsLocationAndEmitsCrossPackets()
            throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        // Anchor the cached PlayerCharacter to row id=0 so save
        // hits our seeded row.
        pl.getCharacter().setMisc(PlayerCharacter.MISC_ID, 0);
        // Start in source zone 7.
        pl.getCharacter().setMisc(PlayerCharacter.MISC_LOCATION, 7);
        pl.updateZone();
        CapturingUDPConnection udp = CapturingUDPConnection.replaceOn(pl);
        CapturingTCPConnection tcp = new CapturingTCPConnection();
        pl.setTcpConnection(tcp);

        // Fire the commit (test ctor — skip the 470ms delay).
        new PortalCrossCommitEvent(buildPortal(946), 0L).execute(pl);

        // MISC_LOCATION moved to destination.
        assertEquals(946, pl.getCharacter()
                .getMisc(PlayerCharacter.MISC_LOCATION));

        // DB persisted.
        try (Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                 "SELECT location FROM player_characters WHERE id=0")) {
            assertTrue(rs.next());
            assertEquals(946, rs.getInt(1));
        }

        // UDP ChangeLocation emitted.
        List<ServerUDPPacket> udpSent = udp.received();
        boolean foundChangeLocation = false;
        for (ServerUDPPacket p : udpSent) {
            if (p instanceof ChangeLocation) foundChangeLocation = true;
        }
        assertTrue("ChangeLocation must be emitted",
                foundChangeLocation);

        // TCP Location + 2× InteractionAck emitted.
        List<ServerTCPPacket> tcpSent = tcp.received();
        int locCount = 0, ackCount = 0;
        for (ServerTCPPacket p : tcpSent) {
            if (p instanceof Location)        locCount++;
            if (p instanceof InteractionAck)  ackCount++;
        }
        assertEquals("exactly one Location (0x83/0x0c)",
                1, locCount);
        assertEquals("exactly two InteractionAck (0xa0/0x02 pair)",
                2, ackCount);
    }

    @Test
    public void disconnectedPlayerIsGracefulNoOp() throws Exception {
        // If the player tore down their UDP during the 470ms cross
        // window the event must NOT commit. Leaving a half-applied
        // state (DB says dest zone, client still in source zone) is
        // worse than dropping the cross.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        pl.getCharacter().setMisc(PlayerCharacter.MISC_ID, 0);
        pl.getCharacter().setMisc(PlayerCharacter.MISC_LOCATION, 7);
        // Tear down UDP before the event fires.
        pl.closeUDP();

        new PortalCrossCommitEvent(buildPortal(946), 0L).execute(pl);

        // MISC_LOCATION stays at source.
        assertEquals(7, pl.getCharacter()
                .getMisc(PlayerCharacter.MISC_LOCATION));
        // DB stays at source (we seeded location=7).
        try (Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                 "SELECT location FROM player_characters WHERE id=0")) {
            assertTrue(rs.next());
            assertEquals(7, rs.getInt(1));
        }
    }

    @Test
    public void nullPlayerIsGracefulNoOp() throws Exception {
        new PortalCrossCommitEvent(buildPortal(946), 0L)
                .execute((Player) null);
        // Pass = no NPE.
    }

    @Test
    public void productionConstructorSchedulesDelayInFuture()
            throws Exception {
        // The public 1-arg ctor must schedule the event ~DELAY_MS in
        // the future (used by UseItem.execute). The 2-arg ctor used
        // by the other tests is package-private and bypasses delay.
        long before = server.tools.Timer.getRealtime();
        PortalCrossCommitEvent e =
                new PortalCrossCommitEvent(buildPortal(946));
        assertTrue("event must fire at least DELAY_MS in the future,"
                + " got " + e.eventTime + " vs now=" + before,
                e.eventTime >= before
                        + PortalCrossCommitEvent.DELAY_MS);
    }

    @Test
    public void delayMatchesRetailTimingGap() {
        // The 470ms gap between TCP 0x83/0x0d and 0x83/0x0c was the
        // pcap-pinned root cause of task #172 (client never
        // transitioned). Belt-and-suspenders: pin the constant so a
        // future refactor can't silently shrink it back to 0.
        assertEquals(470L, PortalCrossCommitEvent.DELAY_MS);
    }

    @Test
    public void executeTransitionsFsmToCrossPendingLocation()
            throws Exception {
        // Task #239 — Phase 1 observation: when the commit fires,
        // the FSM must advance to CROSS_PENDING_LOCATION so any
        // Phase 2 gate (waiting for the client's reliable-ACK of
        // 0x83/0x0c) can see the transition.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        pl.getCharacter().setMisc(PlayerCharacter.MISC_ID, 0);
        pl.getCharacter().setMisc(PlayerCharacter.MISC_LOCATION, 7);
        pl.updateZone();
        CapturingUDPConnection.replaceOn(pl);
        pl.setTcpConnection(new CapturingTCPConnection());

        assertEquals("FSM starts in PRE_LOGIN",
                ClientState.PRE_LOGIN,
                pl.getStateMachine().getState());

        new PortalCrossCommitEvent(buildPortal(946), 0L).execute(pl);

        assertEquals("commit must advance FSM to "
                + "CROSS_PENDING_LOCATION",
                ClientState.CROSS_PENDING_LOCATION,
                pl.getStateMachine().getState());
        assertFalse("FSM transition log must record the advance",
                pl.getStateMachine().recentTransitions().isEmpty());
    }

    @Test
    public void disconnectedPlayerDoesNotTransitionFsm()
            throws Exception {
        // Graceful-no-op contract: if the player tore down their
        // UDP during the 470ms window, the commit drops everything
        // — including the FSM transition. We don't want a stale
        // CROSS_PENDING_LOCATION marker lingering on a closed
        // session.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        pl.getCharacter().setMisc(PlayerCharacter.MISC_ID, 0);
        pl.getCharacter().setMisc(PlayerCharacter.MISC_LOCATION, 7);
        pl.closeUDP();

        new PortalCrossCommitEvent(buildPortal(946), 0L).execute(pl);

        assertEquals("FSM must stay in PRE_LOGIN on the early-out",
                ClientState.PRE_LOGIN,
                pl.getStateMachine().getState());
    }

    @Test
    public void portalAccessorReturnsConstructorValue()
            throws Exception {
        // Defensive: the event carries the resolved portal forward
        // through the 470ms window. Verify it's preserved.
        PortalResolver.Portal p = buildPortal(1573);
        PortalCrossCommitEvent e = new PortalCrossCommitEvent(p, 0L);
        assertSame(p, e.getPortal());
    }

    /** Seed WorldManager.weList so PortalResolver.worldIdToObjectPath
     *  can resolve a destination zone in unit tests. */
    private static void seedWorldManager(int worldId, String worldname)
            throws Exception {
        java.lang.reflect.Field f =
                server.database.worlds.WorldManager.class
                        .getDeclaredField("weList");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.TreeMap<Integer, server.database.worlds.World> we =
                (java.util.TreeMap<Integer,
                        server.database.worlds.World>) f.get(null);
        we.put(worldId,
                new server.database.worlds.World(worldId, worldname));
    }

    @Test
    public void executeMarksDestinationBspAsLoaded()
            throws Exception {
        // Task #253 — after a successful commit, the destination
        // BSP must be registered in the per-Player BSP-load cache
        // so the next cross BACK to it suppresses 0x83/0x0d
        // (retail-faithful: cached BSP → no LoadingBegin).
        //
        // Without this, dungeon → city → dungeon cycles would keep
        // tripping the client's loading state machine on a BSP it
        // already has cached — the suspected root cause of #208
        // "SYNCHRONIZING INTO DUNGEON ZONE" hangs.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        pl.getCharacter().setMisc(PlayerCharacter.MISC_ID, 0);
        pl.getCharacter().setMisc(PlayerCharacter.MISC_LOCATION, 7);
        pl.updateZone();
        CapturingUDPConnection.replaceOn(pl);
        pl.setTcpConnection(new CapturingTCPConnection());

        seedWorldManager(946, "citysewer/peppersewer");
        // worldnameToObjectPath("citysewer/peppersewer") =
        // "worlds/citysewer/pak_peppersewer.dat"
        String expectedKey = "worlds/citysewer/pak_peppersewer.dat";

        assertFalse("destination BSP must NOT be cached before commit",
                pl.hasLoadedBsp(expectedKey));

        new PortalCrossCommitEvent(buildPortal(946), 0L).execute(pl);

        assertTrue("commit must mark destination BSP as loaded so "
                + "future re-crosses suppress 0x83/0x0d",
                pl.hasLoadedBsp(expectedKey));
    }
}
