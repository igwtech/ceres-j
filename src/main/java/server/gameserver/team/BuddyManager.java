package server.gameserver.team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.PlayerManager;

/**
 * In-memory buddy-list state for the running session.
 *
 * <p>Maps {@code ownerUid} → ordered set of buddy UIDs. Persistence
 * (DB column or {@code player_buddies} table) is deferred — Phase 0
 * has the {@code client_defs} JSON store and player CRUD already,
 * so buddy persistence will land alongside the player-save path in
 * a follow-up. For the running session, this manager is the source
 * of truth.
 *
 * <p>Concurrency: the owner→buddies map is a
 * {@link ConcurrentHashMap}, so producers from any thread (login
 * loader, admin command, in-game add) can mutate without holding
 * the world bus tick. Reads use defensive copies so callers can
 * iterate without worrying about external writers.
 *
 * <p>The cross-zone resolution path goes:
 *
 * <ol>
 *   <li>{@link #findOnlineBuddyByName(int, String)} — scan the
 *       owner's buddy list, look up each by name across the
 *       global {@link PlayerManager#getOnlinePlayers()} list.</li>
 *   <li>If found, return their UID; the caller (chat manager, mail
 *       sender) uses the UID for the next packet without caring
 *       which zone the buddy is in.</li>
 * </ol>
 *
 * <p>Future: a TCP S->C "buddy online / offline" push event lands
 * here as an additional broadcast handler; the existing
 * {@link server.gameserver.WorldMessageBus} pattern from chat /
 * drone / mob applies directly.
 */
public final class BuddyManager {

    /** Strategy for finding a Player by display name. Default scans
     *  {@link PlayerManager#getOnlinePlayers()}. */
    @FunctionalInterface
    public interface PlayerByNameLookup {
        Player findByName(String name);
    }

    private static final Map<Integer, Set<Integer>> BUDDIES = new ConcurrentHashMap<>();
    private static PlayerByNameLookup nameLookup =
            BuddyManager::defaultFindByName;

    private BuddyManager() {}

    /** Add {@code buddyUid} to {@code ownerUid}'s buddy list.
     *  Idempotent — re-adding an existing entry is a no-op. */
    public static boolean add(int ownerUid, int buddyUid) {
        if (ownerUid == buddyUid) return false; // can't buddy yourself
        Set<Integer> set = BUDDIES.computeIfAbsent(ownerUid,
                k -> Collections.synchronizedSet(new LinkedHashSet<>()));
        return set.add(buddyUid);
    }

    /** Remove {@code buddyUid} from {@code ownerUid}'s buddy list. */
    public static boolean remove(int ownerUid, int buddyUid) {
        Set<Integer> set = BUDDIES.get(ownerUid);
        if (set == null) return false;
        boolean removed = set.remove(buddyUid);
        if (removed && set.isEmpty()) BUDDIES.remove(ownerUid);
        return removed;
    }

    /** Snapshot of {@code ownerUid}'s buddy list. Returns an empty
     *  list if the owner has no buddies (or doesn't exist). */
    public static List<Integer> listBuddies(int ownerUid) {
        Set<Integer> set = BUDDIES.get(ownerUid);
        if (set == null) return Collections.emptyList();
        synchronized (set) {
            return new ArrayList<>(set);
        }
    }

    /** Test whether {@code buddyUid} is a buddy of {@code ownerUid}. */
    public static boolean isBuddy(int ownerUid, int buddyUid) {
        Set<Integer> set = BUDDIES.get(ownerUid);
        if (set == null) return false;
        synchronized (set) {
            return set.contains(buddyUid);
        }
    }

    /** Resolve a buddy by display name, returning their UID if
     *  online and on the owner's buddy list. Returns null otherwise.
     *  This is the "chat to my friend regardless of zone" fast path. */
    public static Integer findOnlineBuddyByName(int ownerUid, String name) {
        if (name == null || name.isEmpty()) return null;
        Player p = nameLookup.findByName(name);
        if (p == null) return null;
        PlayerCharacter pc = p.getCharacter();
        if (pc == null) return null;
        int uid = pc.getMisc(PlayerCharacter.MISC_ID);
        return isBuddy(ownerUid, uid) ? uid : null;
    }

    /** UIDs of buddies currently online (have a Player object with a
     *  character attached). Order matches the buddy-list insertion. */
    public static List<Integer> onlineBuddies(int ownerUid) {
        List<Integer> all = listBuddies(ownerUid);
        if (all.isEmpty()) return all;
        Set<Integer> online = new LinkedHashSet<>();
        Collection<Player> players = PlayerManager.getOnlinePlayers();
        for (Player p : players) {
            PlayerCharacter pc = p == null ? null : p.getCharacter();
            if (pc != null) online.add(pc.getMisc(PlayerCharacter.MISC_ID));
        }
        List<Integer> out = new ArrayList<>();
        for (Integer uid : all) {
            if (online.contains(uid)) out.add(uid);
        }
        return out;
    }

    /** Number of owners currently tracked. */
    public static int trackedOwners() { return BUDDIES.size(); }

    // ─── Default strategies ─────────────────────────────────────────

    private static Player defaultFindByName(String name) {
        for (Player p : PlayerManager.getOnlinePlayers()) {
            if (p == null) continue;
            PlayerCharacter pc = p.getCharacter();
            if (pc == null) continue;
            if (name.equalsIgnoreCase(pc.getName())) return p;
        }
        return null;
    }

    // ─── Test seams ─────────────────────────────────────────────────

    public static void setNameLookupForTesting(PlayerByNameLookup l) {
        nameLookup = (l == null) ? BuddyManager::defaultFindByName : l;
    }

    public static void resetForTesting() {
        BUDDIES.clear();
        nameLookup = BuddyManager::defaultFindByName;
    }
}
