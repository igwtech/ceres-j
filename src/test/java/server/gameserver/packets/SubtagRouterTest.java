package server.gameserver.packets;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import server.gameserver.packets.client_udp.UnknownClientUDPPacket;
import server.interfaces.GameServerEvent;

public class SubtagRouterTest {

    @After
    public void tearDown() { SubtagRouter.clearAllForTesting(); }

    static final class FakeEvent implements GameServerEvent {
        final String tag;
        FakeEvent(String tag) { this.tag = tag; }
        @Override public long getEventTime() { return 0; }
        @Override public int compareTo(server.interfaces.Event o) { return 0; }
        @Override public void execute(server.gameserver.Player pl) { /* no-op */ }
        @Override public void execute(server.gameserver.GameServerTCPConnection tcp) { /* no-op */ }
    }

    @Test
    public void exactMatchWins() {
        SubtagRouter.register(0x03, 0x1f, 0x25, 0x04,
                bytes -> new FakeEvent("cash"));
        SubtagRouter.register(0x03, 0x1f, 0x25, -1,
                bytes -> new FakeEvent("any-25"));
        // Most-specific match must win.
        GameServerEvent e = SubtagRouter.dispatch(new byte[]{1, 2, 3},
                0x03, 0x1f, 0x25, 0x04);
        assertNotNull(e);
        assertEquals("cash", ((FakeEvent) e).tag);
    }

    @Test
    public void shallowFallback() {
        SubtagRouter.register(0x03, 0x1f, 0x25, -1,
                bytes -> new FakeEvent("any-25"));
        // Subtag 0x99 has no exact match; falls back to 0x25/*.
        GameServerEvent e = SubtagRouter.dispatch(new byte[]{1, 2},
                0x03, 0x1f, 0x25, 0x99);
        assertNotNull(e);
        assertEquals("any-25", ((FakeEvent) e).tag);
    }

    @Test
    public void unknownPathReturnsNull() {
        // No registrations.
        GameServerEvent e = SubtagRouter.dispatch(new byte[]{1, 2},
                0x03, 0x1f, 0x99, 0x99);
        assertNull(e);
    }

    @Test
    public void duplicateRegistrationThrows() {
        SubtagRouter.register(0x03, 0x1f, 0x17, 0,
                bytes -> new FakeEvent("a"));
        try {
            SubtagRouter.register(0x03, 0x1f, 0x17, 0,
                    bytes -> new FakeEvent("b"));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) { /* ok */ }
    }

    @Test
    public void factoryThrowingReturnsUnknown() {
        SubtagRouter.register(0x03, 0x1f, 0x10, 0, bytes -> {
            throw new RuntimeException("boom");
        });
        GameServerEvent e = SubtagRouter.dispatch(new byte[]{1, 2},
                0x03, 0x1f, 0x10, 0);
        assertTrue(e instanceof UnknownClientUDPPacket);
    }
}
