package server.gameserver.packets;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import server.gameserver.packets.client_udp.UnknownClientUDPPacket;
import server.interfaces.GameServerEvent;
import server.tools.Out;

/**
 * Centralized dispatch table for sub-tagged UDP packets.
 *
 * <p>The legacy decode path in {@link GamePacketReaderUDP#decodesub13}
 * is a hand-rolled ladder of nested switches. As we add the chat /
 * trade / mission / drone / vehicle / citycom subtag families
 * (Phases 2-8 of the implementation plan), the ladder would balloon
 * uncontrollably. This router lets new subtags register their
 * factory once and never touch the reader.
 *
 * <p>Keys are encoded as a 64-bit packed path:
 * <pre>
 *   bits 0-7   = depth-0 byte (outer wrapper, e.g. 0x03)
 *   bits 8-15  = depth-1 byte (sub-opcode, e.g. 0x1f)
 *   bits 16-23 = depth-2 byte (tag, e.g. 0x3b)
 *   bits 24-31 = depth-3 byte (sub-tag, e.g. 0x04)
 *   bit 32     = "depth-3 was specified" flag
 *   bit 33     = "depth-2 was specified" flag
 *   bit 34     = "depth-1 was specified" flag
 *   ...
 * </pre>
 *
 * <p>Lookup tries the deepest registered key first (most specific
 * win), then falls back to shallower. This lets a registration like
 * {@code 0x03/0x1f/0x25/0x04} (cash/skill spend) coexist with a
 * {@code 0x03/0x1f/0x25/*} catch-all (transaction wrapper).
 *
 * <p>Phase 0 plumbs the router but does NOT migrate existing dispatch.
 * New subtag families register here directly. The legacy ladder
 * remains the source of truth for already-implemented packets until
 * each one is migrated explicitly.
 */
public final class SubtagRouter {

    /** Build a key for {@code outer/sub/tag/subtag}. Use -1 for any
     *  depth that should NOT be part of the match (i.e. shallower). */
    public static long key(int outer, int sub, int tag, int subtag) {
        long k = outer & 0xff;
        if (sub >= 0)    k |= ((long)(sub & 0xff)) << 8 | (1L << 34);
        if (tag >= 0)    k |= ((long)(tag & 0xff)) << 16 | (1L << 33);
        if (subtag >= 0) k |= ((long)(subtag & 0xff)) << 24 | (1L << 32);
        return k;
    }

    /** A factory that takes the raw inner bytes and returns a decoded
     *  packet event. Implementations typically wrap a
     *  {@link GamePacketDecoderUDP} subclass constructor. */
    @FunctionalInterface
    public interface SubtagFactory extends Function<byte[], GameServerEvent> {}

    private static final Map<Long, SubtagFactory> ROUTES = new HashMap<>();

    private SubtagRouter() {}

    /** Register a factory for the given path. Duplicates throw —
     *  registrations should happen exactly once at startup. */
    public static synchronized void register(int outer, int sub, int tag,
                                              int subtag, SubtagFactory f) {
        long k = key(outer, sub, tag, subtag);
        if (ROUTES.containsKey(k)) {
            throw new IllegalStateException(String.format(
                "SubtagRouter: duplicate registration for %02x/%02x/%02x/%02x",
                outer, sub, tag, subtag));
        }
        ROUTES.put(k, f);
    }

    /** Most-specific match wins. Try (outer/sub/tag/subtag) first,
     *  then drop subtag, then drop tag, etc. Returns null if no
     *  registration matches. */
    public static GameServerEvent dispatch(byte[] subPacket,
                                            int outer, int sub,
                                            int tag, int subtag) {
        // outer/sub/tag/subtag → outer/sub/tag → outer/sub → outer
        long[] keys = new long[] {
            key(outer, sub, tag, subtag),
            key(outer, sub, tag, -1),
            key(outer, sub, -1, -1),
            key(outer, -1, -1, -1),
        };
        for (long k : keys) {
            SubtagFactory f = ROUTES.get(k);
            if (f != null) {
                try {
                    return f.apply(subPacket);
                } catch (Exception e) {
                    Out.writeln(Out.Error, String.format(
                        "SubtagRouter: factory for %02x/%02x/%02x/%02x threw: %s",
                        outer, sub, tag, subtag, e.getMessage()));
                    return new UnknownClientUDPPacket(subPacket);
                }
            }
        }
        return null;
    }

    /** True if any factory is registered at the given path level. */
    public static boolean hasRoute(int outer, int sub, int tag, int subtag) {
        return ROUTES.containsKey(key(outer, sub, tag, subtag));
    }

    /** For tests / admin: clear all registrations. */
    public static synchronized void clearAllForTesting() {
        ROUTES.clear();
    }

    /** Number of registered routes. */
    public static int routeCount() { return ROUTES.size(); }
}
