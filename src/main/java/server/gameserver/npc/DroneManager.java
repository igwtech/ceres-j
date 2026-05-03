package server.gameserver.npc;

import java.util.Collection;
import java.util.Collections;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.PlayerManager;
import server.gameserver.WorldMessageBus;
import server.gameserver.Zone;
import server.tools.Out;

/**
 * Handler that consumes {@link DroneControlIntent}s posted by the
 * pilot's {@code DroneControlPacket} and fans out the resulting
 * drone-position {@link MobStateBroadcast} to other players in the
 * pilot's zone.
 *
 * <p>Phase 3 part 2 — closes the drone control loop: the client
 * sends a 41-byte C->S frame at ~10 Hz, the server posts an intent
 * to the world bus, and this manager echoes the position to nearby
 * players so they see the drone moving. Real proximity filtering
 * (within sensor range) is deferred to Phase 6 (vehicle / parent
 * entity) — for now every player in the same zone receives the
 * broadcast.
 *
 * <p>The pilot is always excluded from the recipient set: the client
 * is already showing the drone at its own input position; the echo
 * would just duplicate work and risk a feedback loop.
 *
 * <p>Concurrency: registered on the world bus, so handlers run on
 * the {@link server.gameserver.WorldTickScheduler} thread — never on
 * the producer (UDP-receive) thread. Test seams allow swapping the
 * lookup + sink so unit tests can verify dispatch without booting
 * the full server stack.
 */
public final class DroneManager {

    /** Strategy for finding the pilot Player given a UID. Default
     *  walks {@link PlayerManager#getOnlinePlayers()}. */
    @FunctionalInterface
    public interface PilotLookup {
        Player findByUid(int uid);
    }

    /** Strategy for collecting the recipients (everyone in the
     *  pilot's zone except the pilot). Default uses
     *  {@link Zone#getAllPlayers()}. */
    @FunctionalInterface
    public interface RecipientFinder {
        Collection<Player> recipientsFor(Player pilot);
    }

    /** Strategy for sending a single echo packet. Default builds a
     *  {@link MobStateBroadcast} bound to the recipient's UDP
     *  session and calls {@link Player#send(server.interfaces.ServerUDPPacket)}. */
    @FunctionalInterface
    public interface PacketSink {
        void send(Player recipient, DroneControlIntent intent);
    }

    private static PilotLookup     pilotLookup     = DroneManager::defaultPilotLookup;
    private static RecipientFinder recipientFinder = DroneManager::defaultRecipients;
    private static PacketSink      packetSink      = DroneManager::defaultSend;

    private DroneManager() {}

    public static void installBusHandlers(WorldMessageBus bus) {
        bus.registerHandler(DroneControlIntent.class,
                DroneManager::dispatchDroneIntent);
    }

    /** Package-private: dispatch a single drone intent. */
    static void dispatchDroneIntent(DroneControlIntent intent) {
        if (intent == null) return;
        Player pilot = pilotLookup.findByUid(intent.pilotUid);
        if (pilot == null) {
            Out.writeln(Out.Warning,
                "DroneManager: pilot uid="
                + Integer.toHexString(intent.pilotUid) + " not online");
            return;
        }
        for (Player r : recipientFinder.recipientsFor(pilot)) {
            if (r == null || r == pilot) continue;
            try {
                packetSink.send(r, intent);
            } catch (Exception e) {
                Out.writeln(Out.Error,
                    "DroneManager: send failed: " + e.getMessage());
            }
        }
    }

    /** Build the echo packet bound to a specific recipient. Public
     *  so tests can construct it without going through the sink.
     *  COMBAT state is used because every observed drone control
     *  frame in the corpus came from an active flight session — a
     *  drone in transit broadcasts as if it were a hostile mob. */
    public static MobStateBroadcast buildEchoFor(Player recipient,
                                                  DroneControlIntent intent) {
        return new MobStateBroadcast(recipient, intent.droneId,
                MobState.COMBAT, 0x00, intent.posZ,
                MobDataDecoder.NO_TARGET, MobStateBroadcast.ZERO_TAIL);
    }

    // ─── Default strategies ─────────────────────────────────────────

    private static Player defaultPilotLookup(int uid) {
        for (Player pl : PlayerManager.getOnlinePlayers()) {
            if (pl == null) continue;
            PlayerCharacter pc = pl.getCharacter();
            if (pc == null) continue;
            if (pc.getMisc(PlayerCharacter.MISC_ID) == uid) return pl;
        }
        return null;
    }

    private static Collection<Player> defaultRecipients(Player pilot) {
        Zone z = pilot.getZone();
        if (z == null) return Collections.emptyList();
        return z.getAllPlayers();
    }

    private static void defaultSend(Player recipient,
                                     DroneControlIntent intent) {
        recipient.send(buildEchoFor(recipient, intent));
    }

    // ─── Test seams ─────────────────────────────────────────────────

    public static void setPilotLookupForTesting(PilotLookup l) {
        pilotLookup = (l == null) ? DroneManager::defaultPilotLookup : l;
    }

    public static void setRecipientFinderForTesting(RecipientFinder f) {
        recipientFinder = (f == null) ? DroneManager::defaultRecipients : f;
    }

    public static void setPacketSinkForTesting(PacketSink s) {
        packetSink = (s == null) ? DroneManager::defaultSend : s;
    }

    public static void resetForTesting() {
        pilotLookup     = DroneManager::defaultPilotLookup;
        recipientFinder = DroneManager::defaultRecipients;
        packetSink      = DroneManager::defaultSend;
    }
}
