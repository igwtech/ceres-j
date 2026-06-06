package server.gameserver.internalEvents;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.server_udp.CharInfo;
import server.gameserver.packets.server_udp.CharInfoV1;
import server.gameserver.packets.server_udp.ChatList;
import server.gameserver.packets.server_udp.InfoResponse;
import server.gameserver.packets.server_udp.InitInfoResponse02;
import server.gameserver.packets.server_udp.InitSoullight02;
import server.gameserver.packets.server_udp.InitUpdateModel02;
import server.gameserver.packets.server_udp.InitWeather02;
import server.gameserver.packets.server_udp.LongPlayerInfo;
import server.gameserver.packets.server_udp.PlayerPositionUpdate;
import server.gameserver.packets.server_udp.PositionUpdate;
import server.gameserver.packets.server_udp.TimeSync;
import server.gameserver.packets.server_udp.UDPAlive;
import server.gameserver.packets.server_udp.UpdateModel;
import server.gameserver.packets.server_udp.WorldWeather;
import server.tools.Out;
import server.tools.Timer;

/**
 * Streams the full world-entry packet sequence to a freshly-logged-in player.
 *
 * The modern NCE 2.5 client times out on a loading screen if it does not
 * receive the full post-handshake burst: character data, model, position,
 * world weather, other players, NPCs and finally a ZoningEnd terminator.
 *
 * Retail server behaviour observed in pcaps (zone pepper/pepper_p3):
 * <ul>
 *   <li>Immediately after the UDP handshake completes, retail sends ~15
 *       large (~440 byte) 0x13 packets containing CharInfo, UpdateModel,
 *       PositionUpdate, LongPlayerInfo for self, ShortPlayerInfo for self,
 *       weather, zone NPCs, and a ZoningEnd terminator.</li>
 *   <li>After a short delay the client sends back an acknowledgment sub-packet
 *       and the server follows up with further zone state (other players,
 *       items).</li>
 * </ul>
 *
 * This event serialises the above burst. It's scheduled from
 * {@code HandshakeUDP} once the 3-way UDP handshake finishes, and it drains
 * all required packets into the player's UDP socket in the order the client
 * expects.
 */
public class WorldEntryEvent extends DummyEvent {

    /** Delay (ms) from the final UDP handshake ack before streaming begins. */
    public static final long START_DELAY_MS = 50;

    public WorldEntryEvent() {
        eventTime = Timer.getRealtime() + START_DELAY_MS;
    }

    public WorldEntryEvent(long delayMs) {
        eventTime = Timer.getRealtime() + delayMs;
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || pl.getCharacter() == null) {
            Out.writeln(Out.Error, "WorldEntryEvent: player or character missing, aborting");
            return;
        }
        PlayerCharacter pc = pl.getCharacter();
        int mapId = pl.getMapID();

        // Task #243 — FSM observation: world-entry burst begins.
        // Covers both first login and cross-reconnect WorldEntries;
        // distinguishing the two in the transition reason lets the
        // log identify which path produced any subsequent issues.
        pl.getStateMachine().transition(
            server.gameserver.state.ClientState.WORLDENTRY_BURST,
            "WorldEntryEvent: begin (mapId=" + mapId + ")");

        // A fresh world-entry (login or post-cross reconnect) is a
        // clean slate: drop any half-finished zone-cross intent so a
        // never-completed Zoning1 can't leave pendingZoneId stuck
        // (which would permanently suppress the UDPAlive heartbeat).
        pl.setPendingZoneId(0);

        // ── Defensive pool rehydration (task #202 / #204) ──────────
        // Retail-faithful semantics (user-confirmed 2026-05-19):
        // logout-while-dead must STAY dead on next login — the client
        // re-shows the respawn overlay and the server gates revive on
        // genrep selection (synaptic). DO NOT auto-restore cur≤0 here:
        // that is a legitimate death-state, not corruption.
        //
        // We DO repair max≤0 to a playable default — that path is only
        // hit by legacy/corrupted rows (an honest character can never
        // mutate its max below 0 through the wire), and a max=0 HUD is
        // not recoverable in-game. Cur>max is clamped down because the
        // bucket-sum HUD recompute (FUN_0080c660) cannot handle it.
        // The death-on-login re-emit lives in the world-entry burst
        // below (PlayerDeath after the CharInfo apply, task #210).
        rehydratePool("HP", pc.getHealth(), pc.getMaxHealth(),
                pc::setHealth, pc::setMaxHealth);
        rehydratePool("PSI", pc.getPsi(), pc.getMaxPsi(),
                pc::setPsi, pc::setMaxPsi);
        rehydratePool("STA", pc.getStamina(), pc.getMaxStamina(),
                pc::setStamina, pc::setMaxStamina);

        // Record the persisted-dead state BEFORE rehydration so the
        // burst below can re-emit PlayerDeath without the rehydrate
        // having to know about it. (rehydratePool only mutates cur
        // when it's clamped above a repaired max — never when cur is
        // simply <=0 — so this snapshot is stable.)
        final boolean persistedDead = pc.getHealth() <= 0;

        Out.writeln(Out.Info, "WorldEntryEvent: streaming world state for "
                + pc.getName() + " mapId=" + mapId);

        // ── Keepalive ─────────────────────────────────────────────────
        safeSend(pl, () -> new UDPAlive(pl), "UDPAlive (pre-stream)");

        // ── NO reliable window-init primer on the LOGIN path ──────────
        // REVERTED 2026-05-31 (reverse-engi2). A prior fix emitted a
        // ZoningEnd (sub-op 0x08) here as seq=1 to "re-base" the client's
        // reliable window. Live apartment-idle wire diff PROVED it is the
        // CAUSE of the NAK storm, not the cure:
        //   - Decoded retail's S→C reliable DATA stream at login: it is
        //     ALL normal windowed ops (0x2e/0x1f/0x2c/0x07/0x23/0x0d/...).
        //     Retail NEVER sends 0x08 as a server reliable DATA packet —
        //     0x08 is the C→S reliable-ACK op (see reliable_ack_08 note).
        //   - With the 0x08 primer at seq=1, the client received it
        //     (cipher + delivery verified byte-exact) but could not
        //     advance its reliable window BASE past a control op it does
        //     not treat as windowed data. Base stuck at 1 ⇒ the client
        //     NAK-stormed seqs 1,2,3,… forever (raw 0x01 ×36/4s) and the
        //     server answered each with a 0x02 retransmit (the "0x02
        //     over-use" livelock). Retail's window advances past its
        //     normal-data seq=1 and NAKs stop after the initial 1,2,3.
        // So the login burst must START with real windowed data. CharInfo
        // (0x2c) as seq=1 is exactly what retail does — the client windows
        // it normally and advances. (Zone-cross via Zoning2 is a separate
        // path; the memory note's "retail emits 0x08 at RESET bursts" is
        // about zone-cross, not login, and is evaluated separately.)

        // ── Phase 1: CharInfo multipart (retail sends this first) ────
        // Retail sends ONE multipart stream: 0x22 0x02 0x01 (CharsysInfo)
        // with per-fragment header discriminator=0x01. FUN_0055c270
        // reads the discriminator to set bit 0 (CharInfo received) and
        // the payload byte[1]=0x02 to set bit 1 (CharsysInfo received).
        //
        // REMOVED: CharInfoV1 as a separate multipart. Sending two
        // multipart objects with the same chain_key=0x00 corrupts the
        // reassembly — the client sees total_size=72 from CharInfoV1's
        // header and truncates the CharInfo data that follows. Retail
        // never sends a separate 0x22 0x01 payload.
        safeSend(pl, () -> new CharInfo(pl), "CharInfo / CharsysInfo (bits 0+1)");

        // ── Phase 2: 0x02 wrapper initialization packets ────────────
        // Retail sends these via the 0x02 "simplified reliable" wrapper
        // right after CharInfo multipart. They carry initialization
        // data the client needs: session flags, weather/time of day,
        // player model, and Soullight value. Without them the client
        // may not fully initialize its core data structures.
        safeSend(pl, () -> new InitInfoResponse02(pl), "0x02 InfoResponse (session flags)");
        safeSend(pl, () -> new InitWeather02(pl), "0x02 Weather (time of day)");
        safeSend(pl, () -> new InitUpdateModel02(pl), "0x02 UpdateModel (appearance)");
        safeSend(pl, () -> new InitSoullight02(pl), "0x02 Soullight (soul energy=100.0)");

        // ── Worldserver handoff staging (gamedata 0x19/0x04) ────────
        // Populates the client's worldserver IP/port. On this initial
        // entry the client is NOT yet in world-change mode (+0x144==0),
        // so its 0x19/0x04 handler stages the address into +0x28c… for
        // the NEXT world-change (a zone cross) to copy into +0x2cc/
        // +0x2d0 — the fields the "Joining session" connect() uses.
        // Without this the client reaches Joining-session on a
        // plaza_p1→plaza_p3 / p2 cross with uninitialised worldserver
        // fields, logs "@PWORLDHOST Connect to <garbage>, 12000" /
        // "Connecting to WorldServer failed", times out ~15 s and
        // reverts to the source zone. WorldInfoSrv was fully
        // implemented + documented but never wired anywhere — this is
        // the missing send. See WorldInfoSrv javadoc + task #172.
        safeSend(pl, () -> new server.gameserver.packets.server_udp
                .WorldInfoSrv(pl), "WorldInfoSrv (0x19/0x04 handoff)");

        // ── Phase 3: InfoResponse + TimeSync (retail pkt #11) ────────
        // InfoResponse zone variant (0x03→0x23): 20 00 10 00 00 00
        // Observed in retail right after CharInfo multipart completes.
        safeSend(pl, () -> InfoResponse.zoneInfo(pl), "InfoResponse (zone info)");

        // TimeSync — server time baseline.
        safeSend(pl, () -> new TimeSync(pl, 0), "TimeSync");

        // ── Phase 3: ChatList + InfoResponse + zone data (retail pkt #12) ──
        // ChatList (0x03→0x33): ff 00 — chat channel list.
        safeSend(pl, () -> new ChatList(pl), "ChatList");

        // InfoResponse session variant (0x03→0x23): 0e 00 ... 01 00
        safeSend(pl, () -> InfoResponse.sessionInfo(pl), "InfoResponse (session info)");

        // Player position / zone entry data. The client REQUIRES an
        // authoritative StartPos to complete world entry — skipping
        // it on a cross-reconnect (experiment 2026-05-16) left the
        // client stuck on the splash forever, so the 0x2c StartPos is
        // ALWAYS sent (even for a city cross — removing it regresses to
        // the splash hang, and the retail-faithful "no StartPos" only
        // holds on the lightweight zone-handoff path that never runs
        // WorldEntryEvent anyway).
        safeSend(pl, () -> new PositionUpdate(pl),
                "PositionUpdate (start pos)");
        safeSend(pl, () -> new WorldWeather(pl), "WorldWeather");

        // The player's own long/short info + position.
        safeSend(pl, () -> new LongPlayerInfo(pl, pc, mapId), "LongPlayerInfo (self)");
        // The 0x03/0x1b self-position is what OVERRIDES the client's own
        // seam self-positioning. RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT
        // shows retail sends NO self-position after a city walk-cross
        // (zone_portal_params.md §7); Ceres pushing the stale source-zone
        // coords here is the task #174 "spawn reset to map centre". For a
        // city cross, suppress the self-position and let the client
        // self-position from local .dat geometry (the StartPos above
        // still keeps the client off the splash). Fresh logins and
        // wasteland/outdoor are unaffected.
        if (pl.isPendingCityCrossSelfPosSuppress()) {
            Out.writeln(Out.Info,
                "WorldEntryEvent: city walk-cross — suppressing self"
                + " PlayerPositionUpdate (client self-positions,"
                + " retail-faithful) for " + pc.getName());
        } else {
            safeSend(pl, () -> new PlayerPositionUpdate(pl, pc, mapId), "PlayerPositionUpdate (self)");
        }

        // ── Phase 4: UpdateModel (retail sends via 0x02 wrapper, we use 0x03) ──
        safeSend(pl, () -> new UpdateModel(pl), "UpdateModel");

        // ── Phase 5: Zone population (other players, NPCs) ──────────
        Zone zone = pl.getZone();
        if (zone != null) {
            try {
                zone.sendPlayersinZone(pl);
            } catch (Exception e) {
                Out.writeln(Out.Error, "WorldEntryEvent: sendPlayersinZone failed: " + e.getMessage());
            }
            try {
                zone.sendNPCsinZone(pl);
            } catch (Exception e) {
                Out.writeln(Out.Error, "WorldEntryEvent: sendNPCsinZone failed: " + e.getMessage());
            }
            try {
                zone.sendnewPlayerinZone(pl);
            } catch (Exception e) {
                Out.writeln(Out.Error, "WorldEntryEvent: sendnewPlayerinZone failed: " + e.getMessage());
            }
        }

        // ── NO trailing ZoningEnd terminator ────────────────────────
        // Retail does NOT send a ZoningEnd (0x03→0x08) as a TERMINATOR
        // at the END of the login burst. (The single 0x08 we now emit
        // is the seq=1 window-init PRIMER at the very START of the
        // burst — see the "Reliable window-init primer" block above.
        // Sending a second 0x08 here would confuse the client's state
        // machine, which is why the trailing terminator stays removed.)

        // ── Heartbeats: start NOW on first login ──────────────────
        // Original design deferred heartbeats until after a zone-
        // handoff (HandshakeUDP starts them when the client closes
        // its UDP socket and reopens from a fresh ephemeral port,
        // typically ~11 s into the session). The intent was to
        // avoid losing reliable packets to a soon-to-close socket.
        //
        // But not every session triggers a zone-handoff — the user-
        // reported 2026-05-09 SYNCHRONIZING-overlay hang showed a
        // session where the client never closed its login socket,
        // so heartbeats never started, and TimeSync (which advances
        // the client's state-machine 3/6 → 4 per
        // FUN_0055b6f0 case 3) never streamed.
        //
        // Starting heartbeats NOW is safe: PlayerUdpListener's
        // rebindClient() redirects future packets when the client
        // does open a new socket, so at most a small handful of
        // heartbeats are lost during the close-and-reopen window —
        // and self-rescheduling means the client picks back up
        // automatically once rebound. ReliableTimeSyncRequest (the
        // explicit 0x03/0x0d C→S request) was the only thing
        // keeping previous sessions alive at all.
        pl.addEvent(new TimeSyncHeartbeatEvent());
        // ── 0x03/0x0d TimeSync push every 30 s ────────────────────
        // Distinct channel from the heartbeat above (which is the
        // ~1 Hz 0x03/0x1f state-ack). This event is the actual
        // clock-advance packet retail emits unsolicited at ~32 s
        // cadence — without it the HUD clock stays frozen at the
        // world-entry baseline (task #231). See TimeSyncPushEvent.
        pl.addEvent(new TimeSyncPushEvent());
        pl.addEvent(new PoolStatusHeartbeat());
        pl.addEvent(new ZoneStateHeartbeat());
        // ── UDPAlive keepalive (0x04) every ~3 s ──
        // Retail emits 8 UDPAlives per HANNIBAL session: 4 in the
        // handshake-reply burst + 4 periodic at ~3 s spacing.
        // Without the periodic ones the client's UDP-keepalive
        // expectation drifts and the harness's spare-UDPAlive
        // skip predicate (PcapReplayTest.isSpareUDPAlive) had to
        // be added to absorb the gap. See task #158.
        pl.addEvent(new UDPAliveHeartbeat());

        // ── TCP keepalive (0x83 0x8f) every ~10 s ──
        // Retail sends this on the TCP connection for the entire
        // session. Without it the client's TCP layer may time out.
        pl.addEvent(new TcpKeepaliveEvent());

        // ── Resource probe (one-shot RE harness, disabled) ──
        // ResourceProbeEvent cycles HP/PSI/STA/Soullight/Cash through
        // known distinct values to correlate server send-order with HUD
        // response. Test on 2026-04-25 confirmed PoolUpdate / PoolStatus /
        // SoullightUpdate / CashUpdateProbe(sub=0x04) DO NOT move the
        // self-HUD — those packets target foreign entities only. The
        // probe code is kept in the tree (commented out here) so it can
        // be re-enabled to test future candidate sub-opcodes.
        // Disabled: ResourceProbeEvent — see PROTOCOL.md "ResourceProbe test".
        // pl.addEvent(new ResourceProbeEvent());

        // Mark the player as waiting for a UDP zone-handoff handshake.
        // Once the client finishes loading the zone descriptor it closes
        // the login UDP socket and opens a fresh one from a new ephemeral
        // port. ListenerUDP uses this flag (plus source IP match) to pair
        // the incoming handshake with the right player, which is the only
        // disambiguation we have for multi-boxed clients on the same IP.
        pl.markHandoffPending();

        // The city-cross self-position suppression flag is single-shot:
        // it must only affect the one world-state burst triggered by the
        // SZoning1Confirm that set it. Clear it now that this burst has
        // run, so a later non-cross re-entry (genrep / relog / fresh
        // login that does NOT route through Zoning1) still gets its
        // authoritative self-position. Outdoor crosses already clear it
        // implicitly (Zoning1 calls setPendingCityCrossSelfPosSuppress
        // (false) for worldId >= 2001); this closes the city-cross leak.
        if (pl.isPendingCityCrossSelfPosSuppress()) {
            pl.clearPendingCityCrossSelfPosSuppress();
            Out.writeln(Out.Info,
                "WorldEntryEvent: city walk-cross self-pos suppression"
                + " consumed and cleared for " + pc.getName());
        }

        // ── Retail-faithful death persistence (task #202 / #210) ──
        // If the character was persisted dead (cur HP ≤ 0), re-emit
        // the death packet AFTER the CharInfo apply so the client
        // re-shows the respawn overlay. Revive is then gated on
        // genrep selection (the C→S genrep-pick packet, task #203).
        // RespawnEvent is NOT scheduled here — retail does not
        // auto-respawn a logged-back-in dead player.
        if (persistedDead) {
            Out.writeln(Out.Info,
                "WorldEntryEvent: " + pc.getName()
                + " logged in dead (HP=" + pc.getHealth()
                + ") — re-emitting PlayerDeath for respawn overlay");
            safeSend(pl, () -> new server.gameserver.packets.server_udp
                    .PlayerDeath(pl, 0),
                    "PlayerDeath (login-while-dead)");
        }

        // Task #243 — FSM observation: world-entry burst finished;
        // the client now has every initial-state packet it needs.
        // Phase 2 callers can register onceAcked() against the
        // post-burst seq to gate further work on the client having
        // settled into IN_WORLD.
        pl.getStateMachine().transition(
            server.gameserver.state.ClientState.IN_WORLD,
            "WorldEntryEvent: complete");

        // Task #253 — mark the current BSP as loaded so future
        // crosses back to this BSP (cross-OUT, re-login portal)
        // suppress 0x83/0x0d LoadingBegin (retail-faithful).
        //
        // CRITICAL (2026-05-24): the cache key must match the
        // string `UseItem` uses for the suppression check.
        // PortalResolver.worldIdToObjectPath returns the
        // "worlds/<dir>/pak_<base>.dat" form. Marking the raw
        // "plaza/plaza_p1" worldname here would be a cache MISS
        // on lookup → 830D would still fire on cross-OUT and the
        // client would hang on SYNCHRONIZING (#208).
        server.gameserver.Zone z = pl.getZone();
        if (z != null && z.getWorldname() != null) {
            int zoneId = pc.getMisc(
                server.database.playerCharacters.PlayerCharacter
                    .MISC_LOCATION);
            String bspKey = server.gameserver.PortalResolver
                .worldIdToObjectPath(zoneId, z.getWorldname());
            if (bspKey != null) {
                pl.markBspLoaded(bspKey);
                Out.writeln(Out.Info,
                    "WorldEntryEvent: marked spawn BSP loaded — "
                    + bspKey + " (for " + pc.getName() + ")");
            }
        }

        Out.writeln(Out.Info, "WorldEntryEvent: completed for " + pc.getName());
    }

    /** Functional helper so each packet instantiation is isolated from errors. */
    @FunctionalInterface
    private interface PacketFactory {
        server.interfaces.ServerUDPPacket build();
    }

    private void safeSend(Player pl, PacketFactory factory, String label) {
        try {
            pl.send(factory.build());
        } catch (Exception e) {
            Out.writeln(Out.Error, "WorldEntryEvent: " + label + " failed: " + e.getMessage());
        }
    }

    /** Default max value applied when both cur and max persisted as 0
     *  (legacy row, corrupted DB). Matches the {@link PlayerCharacter}
     *  field-init default of 100. */
    private static final int FALLBACK_MAX = 100;

    /** Visible for unit tests. Repairs the persistence-corruption
     *  cases only: {@code max≤0} (legacy/corrupted row — an honest
     *  in-game path never mutates max below 0) and {@code cur>max}
     *  (would break the bucket-sum HUD recompute).
     *
     *  <p>Crucially this does <b>not</b> restore {@code cur≤0} — that
     *  is a legitimate death-state which retail expects to persist
     *  across logout. The login burst re-emits {@code PlayerDeath} so
     *  the client shows the respawn overlay; revive is gated on
     *  genrep selection (task #203 / #210). */
    static void rehydratePool(String label, int cur, int max,
            java.util.function.IntConsumer setCur,
            java.util.function.IntConsumer setMax) {
        int repairedMax = max;
        if (repairedMax <= 0) {
            Out.writeln(Out.Warning,
                "WorldEntryEvent: " + label + " max=" + max
                + " — defaulting to " + FALLBACK_MAX
                + " (corrupted row repair)");
            repairedMax = FALLBACK_MAX;
            setMax.accept(repairedMax);
        }
        // cur≤0 is left as-is: retail-faithful death persistence.
        if (cur > repairedMax) {
            // Clamp down: cur > max breaks the bucket-sum HUD recompute
            // (FUN_0080c660 expects sum ≤ anchor).
            Out.writeln(Out.Warning,
                "WorldEntryEvent: " + label + " cur=" + cur
                + " exceeds max=" + repairedMax + " — clamping");
            setCur.accept(repairedMax);
        }
    }
}
