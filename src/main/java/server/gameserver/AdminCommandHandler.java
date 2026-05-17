package server.gameserver;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Packet830D;
import server.gameserver.packets.server_udp.ForcedZoning;
import server.gameserver.packets.server_udp.LocalChatMessage;
import server.gameserver.packets.server_udp.PoolStatusBroadcast;
import server.gameserver.packets.server_udp.PoolUpdate;
import server.gameserver.packets.server_udp.SoullightUpdate;
import server.gameserver.packets.server_udp.AttributeUpdate3c;
import server.gameserver.packets.server_udp.CashReceipt;
import server.gameserver.packets.server_udp.CashSnapshot;
import server.gameserver.packets.server_udp.CashUpdate;
import server.gameserver.packets.server_udp.CashUpdateProbe;
import server.gameserver.packets.server_udp.LongPlayerInfo;
import server.tools.Out;

/**
 * Handles in-game admin/GM commands typed in chat.
 *
 * <p>Commands start with {@code .} (MaNGOS-style) or the legacy
 * {@code !} prefix and are consumed before broadcasting to the
 * zone. The {@code /} prefix is deliberately NOT accepted: the
 * Neocron client intercepts {@code /}-prefixed input locally
 * (like the WoW client), so those lines never reach the server.
 * All players can use basic commands; GM-level commands are gated
 * by the account GM level.
 *
 * <p>Available commands (examples):
 * <ul>
 *   <li>{@code .pos} — show current Y/Z/X coordinates</li>
 *   <li>{@code .warp <zoneId>} — teleport to a zone</li>
 *   <li>{@code .hp} — show current health/psi/stamina</li>
 *   <li>{@code .heal} — restore HP/PSI/STA to max</li>
 *   <li>{@code .kill} — set HP to 0 (for testing death)</li>
 *   <li>{@code .god} — toggle invincibility</li>
 *   <li>{@code .spawn <type> <x> <y> <z>} — spawn an NPC</li>
 *   <li>{@code .online} — list connected players</li>
 *   <li>{@code .help} — show available commands</li>
 * </ul>
 */
public class AdminCommandHandler {

    /**
     * Try to handle a chat message as a command.
     *
     * @return true if the message was a command (consumed), false if
     *         it should be broadcast as normal chat
     */
    public static boolean handle(Player pl, String message) {
        if (message == null || message.isEmpty()) return false;

        // Strip null bytes and trailing whitespace — readCString()
        // includes everything after offset 7 including null terminators.
        message = message.replace("\0", "").trim();
        if (message.isEmpty()) return false;
        // MaNGOS-style '.' prefix is the canonical one; legacy '!'
        // kept for backward compat (!setmaxhp / !setsub). '/' is
        // intentionally rejected — the client swallows /-input
        // locally so it never reaches the server.
        char prefix = message.charAt(0);
        if (prefix != '.' && prefix != '!') return false;
        String cmdLine = message.substring(1).trim();

        String[] parts = cmdLine.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        // ── New CMaNGOS-style registry (task #179) ────────────────────
        // The registry owns the modern command set with per-command
        // GM-level gating. If it knows this keyword it fully handles
        // dispatch (parse + permission + execute + reply); we never
        // fall through to the legacy switch for a registered name, so
        // there is no double-handling. Unknown-to-registry keywords
        // continue to the legacy switch below (no regression).
        server.gameserver.command.GmCommandRegistry registry =
                server.gameserver.command.GmCommandRegistry.defaultRegistry();
        if (registry.isRegistered(cmd)) {
            server.gameserver.command.CommandResult result =
                    registry.dispatch(pl, cmdLine,
                            msg -> reply(pl, msg));
            switch (result.status()) {
            case OK:
                if (result.message() != null
                        && !result.message().isEmpty()) {
                    reply(pl, result.message());
                }
                break;
            case BAD_SYNTAX:
            case DENIED:
            case ERROR:
            case NOT_FOUND:
                reply(pl, result.message());
                break;
            }
            return true;
        }

        switch (cmd) {
        case "pos":
            cmdPos(pl);
            return true;
        case "warp":
        case "zone":
            cmdWarp(pl, args);
            return true;
        case "warpforce":
        case "fwarp":
            cmdWarpForce(pl, args);
            return true;
        case "hp":
        case "stats":
            cmdStats(pl);
            return true;
        case "heal":
            cmdHeal(pl);
            return true;
        case "damage":
        case "dmg":
            cmdDamage(pl, args);
            return true;
        case "kill":
            cmdKill(pl);
            return true;
        case "god":
            cmdGod(pl);
            return true;
        case "hurtmob":
        case "mobdmg":
            cmdHurtMob(pl, args);
            return true;
        case "hurtself":
        case "selfdmg":
            cmdHurtSelf(pl, args);
            return true;
        case "mobtick":
            cmdMobTick(pl);
            return true;
        case "mobstate":
            cmdMobState(pl, args);
            return true;
        case "buddyadd":
            cmdBuddyAdd(pl, args);
            return true;
        case "buddyrm":
        case "buddyremove":
            cmdBuddyRm(pl, args);
            return true;
        case "buddylist":
            cmdBuddyList(pl);
            return true;
        case "groupcreate":
        case "groupnew":
            cmdGroupCreate(pl);
            return true;
        case "groupinvite":
        case "groupadd":
            cmdGroupInvite(pl, args);
            return true;
        case "groupleave":
        case "groupquit":
            cmdGroupLeave(pl);
            return true;
        case "groupinfo":
            cmdGroupInfo(pl);
            return true;
        case "spawn":
            cmdSpawn(pl, args);
            return true;
        case "online":
        case "players":
            cmdOnline(pl);
            return true;
        case "sethp":   cmdSetHp(pl, args);   return true;
        case "setpsi":  cmdSetPsi(pl, args);  return true;
        case "setsta":  cmdSetSta(pl, args);  return true;
        case "setsl":   cmdSetSoullight(pl, args); return true;
        case "setcash": cmdSetCash(pl, args); return true;
        case "setcashslot": cmdSetCashSlot(pl, args); return true;
        case "setcashreceipt": cmdSetCashReceipt(pl, args); return true;
        case "attr3c": cmdAttr3c(pl, args); return true;
        case "probecash": cmdProbeCash(pl, args); return true;
        case "setsub":  cmdSetSubskill(pl, args); return true;
        case "setmaxhp":cmdSetMaxHp(pl, args); return true;
        case "help":
        case "?":
            cmdHelp(pl);
            return true;
        default:
            reply(pl, "Unknown command: !" + cmd + ". Type !help for list.");
            return true;
        }
    }

    private static void reply(Player pl, String msg) {
        pl.send(new LocalChatMessage(pl, "[Server] " + msg, 2));
    }

    private static void cmdPos(Player pl) {
        PlayerCharacter pc = pl.getCharacter();
        int y = pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE);
        int z = pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE);
        int x = pc.getMisc(PlayerCharacter.MISC_X_COORDINATE);
        reply(pl, "Position: Y=" + y + " Z=" + z + " X=" + x
                + " Zone=" + pl.getMapID());
    }

    private static void cmdDamage(Player pl, String args) {
        float amount = 25.0f;
        if (!args.isEmpty()) {
            try { amount = Float.parseFloat(args.split("\\s+")[0]); }
            catch (NumberFormatException e) { /* use default */ }
        }
        pl.applyDamage(amount, 0);
        reply(pl, "Took " + (int)amount + " damage. HP: "
                + pl.getCharacter().getHealth() + "/" + pl.getCharacter().getMaxHealth());
    }

    /**
     * Smooth zone transition matching retail's TCP `0x83 0x0d` +
     * `0x83 0x0c` flow (verified 2026-05-02 against retail walk
     * pepper_p3 → pepper_p2 → pepper_p1 + GenRep teleport). No splash,
     * no CharInfo redelivery, no UDP ForcedZoning — client loads the
     * new BSP locally and keeps its existing CharInfo state.
     *
     * <p><b>CONSTRAINT:</b> the client only accepts this smooth path
     * when it has already entered the "leaving zone" movement-authority
     * state — i.e., it's physically crossing a zone boundary. Issuing
     * {@code !warp} from arbitrary positions leaves the client stuck
     * on the "SYNCHRONIZING INTO CITY ZONE" overlay because the boundary
     * trigger never fired client-side. Use this command as the future
     * server-side response to detected boundary crossings, NOT as a
     * generic admin teleport.
     *
     * <p>For arbitrary admin teleport from anywhere, use
     * {@code !warpforce} (UDP ForcedZoning, full splash + state
     * re-stream).
     */
    private static void cmdWarp(Player pl, String args) {
        if (args.isEmpty()) {
            reply(pl, "Usage: !warp <zoneId>  (smooth TCP — requires being at a zone boundary; use !warpforce for splash)");
            return;
        }
        try {
            int zoneId = Integer.parseInt(args.split("\\s+")[0]);
            if (zoneId <= 0) {
                reply(pl, "Invalid zone ID: " + zoneId);
                return;
            }
            reply(pl, "Warping to zone " + zoneId + " (smooth TCP — only works at boundary)...");
            pl.getCharacter().setMisc(PlayerCharacter.MISC_LOCATION, zoneId);
            pl.updateZone();
            pl.getTcpConnection().send(new Packet830D());
            pl.getTcpConnection().send(new Location(pl));
        } catch (NumberFormatException e) {
            reply(pl, "Invalid zone ID: " + args);
        }
    }

    private static void cmdWarpForce(Player pl, String args) {
        if (args.isEmpty()) {
            reply(pl, "Usage: !warpforce <zoneId>  (UDP ForcedZoning, splash screen)");
            return;
        }
        try {
            int zoneId = Integer.parseInt(args.split("\\s+")[0]);
            if (zoneId <= 0) {
                reply(pl, "Invalid zone ID: " + zoneId);
                return;
            }
            reply(pl, "Force-warping to zone " + zoneId + " (UDP, splash)...");
            pl.send(new ForcedZoning(pl, zoneId));
        } catch (NumberFormatException e) {
            reply(pl, "Invalid zone ID: " + args);
        }
    }

    private static void cmdStats(Player pl) {
        PlayerCharacter pc = pl.getCharacter();
        reply(pl, "HP: " + pc.getHealth() + "/" + pc.getMaxHealth()
                + " PSI: " + pc.getPsi() + "/" + pc.getMaxPsi()
                + " STA: " + pc.getStamina() + "/" + pc.getMaxStamina());
    }

    private static void cmdHeal(Player pl) {
        PlayerCharacter pc = pl.getCharacter();
        pc.setHealth(pc.getMaxHealth());
        pc.setPsi(pc.getMaxPsi());
        pc.setStamina(pc.getMaxStamina());
        reply(pl, "Healed to full.");
    }

    private static void cmdKill(Player pl) {
        reply(pl, "You died.");
        pl.die();
    }

    private static void cmdGod(Player pl) {
        // Toggle god mode via a simple flag on PlayerCharacter
        // For now just heal and report
        PlayerCharacter pc = pl.getCharacter();
        pc.setHealth(pc.getMaxHealth());
        reply(pl, "God mode: healed to full (toggle not yet implemented).");
    }

    private static void cmdSpawn(Player pl, String args) {
        if (args.isEmpty()) {
            reply(pl, "Usage: /spawn <type> [x y z]");
            return;
        }
        try {
            String[] p = args.split("\\s+");
            int type = Integer.parseInt(p[0]);
            PlayerCharacter pc = pl.getCharacter();
            int x = p.length > 1 ? Integer.parseInt(p[1]) : pc.getMisc(PlayerCharacter.MISC_X_COORDINATE);
            int y = p.length > 2 ? Integer.parseInt(p[2]) : pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE);
            int z = p.length > 3 ? Integer.parseInt(p[3]) : pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE);
            pl.getZone().addNPCtoZone(x, y, z, 100, type, 0);
            reply(pl, "Spawned NPC type=" + type + " at X=" + x + " Y=" + y + " Z=" + z);
        } catch (Exception e) {
            reply(pl, "Spawn failed: " + e.getMessage());
        }
    }

    /**
     * {@code !hurtmob <npcId> <amount>} — post a {@link
     * server.gameserver.npc.MobDamageIntent} for the given mob.
     * Verifies the damage→aggro path end-to-end without needing a
     * weapon-fire packet.
     *
     * <p>Both arguments parse as decimal or 0x-prefixed hex.
     */
    static void cmdHurtMob(Player pl, String args) {
        String[] p = args == null ? new String[0] : args.split("\\s+");
        if (p.length < 2 || p[0].isEmpty()) {
            reply(pl, "Usage: !hurtmob <npcId> <amount>");
            return;
        }
        int npcId; int amount;
        try {
            npcId  = parseIntFlex(p[0]);
            amount = parseIntFlex(p[1]);
        } catch (NumberFormatException e) {
            reply(pl, "Bad argument: " + e.getMessage());
            return;
        }
        WorldMessageBus bus = GameServer.getBus();
        if (bus == null) {
            reply(pl, "World bus not running (no GameServer.init()).");
            return;
        }
        PlayerCharacter pc = pl.getCharacter();
        int attackerUid = (pc == null) ? 0
                : pc.getMisc(PlayerCharacter.MISC_ID);
        Zone z = pl.getZone();
        int zoneId = (z == null) ? -1 : z.getZoneId();
        bus.post(new server.gameserver.npc.MobDamageIntent(
                npcId, zoneId, attackerUid, amount));
        reply(pl, "Posted damage intent: npc=0x"
                + Integer.toHexString(npcId)
                + " amount=" + amount);
    }

    /**
     * {@code !hurtself <amount> [attackerId]} — post a
     * {@link server.gameserver.npc.PlayerDamageIntent} for the
     * caller. Verifies the bus-driven player damage path
     * end-to-end. {@code attackerId} defaults to 0.
     */
    static void cmdHurtSelf(Player pl, String args) {
        String[] p = args == null ? new String[0] : args.split("\\s+");
        if (p.length < 1 || p[0].isEmpty()) {
            reply(pl, "Usage: !hurtself <amount> [attackerId]");
            return;
        }
        int amount; int attackerId;
        try {
            amount     = parseIntFlex(p[0]);
            attackerId = (p.length > 1) ? parseIntFlex(p[1]) : 0;
        } catch (NumberFormatException e) {
            reply(pl, "Bad arg: " + e.getMessage());
            return;
        }
        WorldMessageBus bus = GameServer.getBus();
        if (bus == null) {
            reply(pl, "World bus not running.");
            return;
        }
        int victimUid = ownerUidOf(pl);
        if (victimUid == 0) {
            reply(pl, "No character attached.");
            return;
        }
        bus.post(new server.gameserver.npc.PlayerDamageIntent(
                victimUid, attackerId, amount, 0x0a));
        reply(pl, "Posted player-damage intent: amount=" + amount
                + " attacker=0x" + Integer.toHexString(attackerId));
    }

    /**
     * {@code !mobtick} — force a {@link server.gameserver.npc.MobAIScheduler}
     * tick across the caller's zone. Useful when developing AI rules
     * to trigger a transition without waiting for the 50 ms heartbeat.
     */
    static void cmdMobTick(Player pl) {
        WorldMessageBus bus = GameServer.getBus();
        if (bus == null) {
            reply(pl, "World bus not running.");
            return;
        }
        Zone z = pl.getZone();
        if (z == null) {
            reply(pl, "Player has no zone yet.");
            return;
        }
        int n = server.gameserver.npc.MobAIScheduler.tickZone(bus, z);
        reply(pl, "MobAIScheduler tick: " + n + " state changes in zone "
                + z.getZoneId());
    }

    /**
     * {@code !mobstate <npcId>} — print the {@link
     * server.gameserver.npc.MobManager} snapshot for a mob.
     */
    static void cmdMobState(Player pl, String args) {
        if (args == null || args.isEmpty()) {
            reply(pl, "Usage: !mobstate <npcId>");
            return;
        }
        int npcId;
        try {
            npcId = parseIntFlex(args.trim());
        } catch (NumberFormatException e) {
            reply(pl, "Bad npcId: " + e.getMessage());
            return;
        }
        server.gameserver.npc.MobManager.Snapshot s =
                server.gameserver.npc.MobManager.getSnapshot(npcId);
        if (s == null) {
            reply(pl, "Mob 0x" + Integer.toHexString(npcId) + " not tracked");
            return;
        }
        reply(pl, "Mob 0x" + Integer.toHexString(npcId)
                + ": state=" + s.state
                + " target=0x" + Integer.toHexString(s.targetId)
                + " alt=" + s.altitude);
    }

    // ─── Buddy commands (Phase 4) ──────────────────────────────────

    /** {@code !buddyadd <uid>} — add a buddy to caller's list. */
    static void cmdBuddyAdd(Player pl, String args) {
        if (args == null || args.isEmpty()) {
            reply(pl, "Usage: !buddyadd <uid>");
            return;
        }
        int buddyUid;
        try {
            buddyUid = parseIntFlex(args.trim());
        } catch (NumberFormatException e) {
            reply(pl, "Bad uid: " + e.getMessage());
            return;
        }
        int ownerUid = ownerUidOf(pl);
        if (ownerUid == 0) {
            reply(pl, "No character attached.");
            return;
        }
        boolean added = server.gameserver.team.BuddyManager.add(
                ownerUid, buddyUid);
        reply(pl, added ? "Added buddy 0x" + Integer.toHexString(buddyUid)
                        : "Already on buddy list.");
    }

    /** {@code !buddyrm <uid>} — remove a buddy. */
    static void cmdBuddyRm(Player pl, String args) {
        if (args == null || args.isEmpty()) {
            reply(pl, "Usage: !buddyrm <uid>");
            return;
        }
        int buddyUid;
        try {
            buddyUid = parseIntFlex(args.trim());
        } catch (NumberFormatException e) {
            reply(pl, "Bad uid: " + e.getMessage());
            return;
        }
        int ownerUid = ownerUidOf(pl);
        if (ownerUid == 0) {
            reply(pl, "No character attached.");
            return;
        }
        boolean removed = server.gameserver.team.BuddyManager.remove(
                ownerUid, buddyUid);
        reply(pl, removed ? "Removed buddy 0x" + Integer.toHexString(buddyUid)
                          : "Not on buddy list.");
    }

    /** {@code !buddylist} — print the caller's buddy list. */
    static void cmdBuddyList(Player pl) {
        int ownerUid = ownerUidOf(pl);
        if (ownerUid == 0) {
            reply(pl, "No character attached.");
            return;
        }
        java.util.List<Integer> buddies = server.gameserver.team.BuddyManager
                .listBuddies(ownerUid);
        if (buddies.isEmpty()) {
            reply(pl, "Buddy list empty.");
            return;
        }
        StringBuilder sb = new StringBuilder("Buddies (")
                .append(buddies.size()).append("): ");
        for (Integer uid : buddies) {
            sb.append("0x").append(Integer.toHexString(uid)).append(' ');
        }
        reply(pl, sb.toString().trim());
    }

    // ─── Group commands (Phase 4) ──────────────────────────────────

    /** {@code !groupcreate} — start a new group with caller as leader. */
    static void cmdGroupCreate(Player pl) {
        WorldMessageBus bus = GameServer.getBus();
        if (bus == null) {
            reply(pl, "World bus not running.");
            return;
        }
        int ownerUid = ownerUidOf(pl);
        if (ownerUid == 0) {
            reply(pl, "No character attached.");
            return;
        }
        int gid = server.gameserver.team.GroupManager.createGroup(bus,
                ownerUid);
        reply(pl, gid > 0 ? "Created group #" + gid
                          : "Already in a group.");
    }

    /** {@code !groupinvite <uid>} — add a member to the caller's group. */
    static void cmdGroupInvite(Player pl, String args) {
        if (args == null || args.isEmpty()) {
            reply(pl, "Usage: !groupinvite <uid>");
            return;
        }
        WorldMessageBus bus = GameServer.getBus();
        if (bus == null) {
            reply(pl, "World bus not running.");
            return;
        }
        int memberUid;
        try {
            memberUid = parseIntFlex(args.trim());
        } catch (NumberFormatException e) {
            reply(pl, "Bad uid: " + e.getMessage());
            return;
        }
        int ownerUid = ownerUidOf(pl);
        if (ownerUid == 0) {
            reply(pl, "No character attached.");
            return;
        }
        Integer gid = server.gameserver.team.GroupManager.groupIdOf(ownerUid);
        if (gid == null) {
            reply(pl, "You are not in a group. Run !groupcreate first.");
            return;
        }
        boolean ok = server.gameserver.team.GroupManager.addMember(bus, gid,
                memberUid);
        reply(pl, ok ? "Invited 0x" + Integer.toHexString(memberUid)
                       + " to group #" + gid
                     : "Could not add member (already in a group, or "
                       + "team is full).");
    }

    /** {@code !groupleave} — leave the caller's current group. */
    static void cmdGroupLeave(Player pl) {
        WorldMessageBus bus = GameServer.getBus();
        if (bus == null) {
            reply(pl, "World bus not running.");
            return;
        }
        int ownerUid = ownerUidOf(pl);
        if (ownerUid == 0) {
            reply(pl, "No character attached.");
            return;
        }
        boolean removed = server.gameserver.team.GroupManager.removeMember(
                bus, ownerUid);
        reply(pl, removed ? "Left group." : "You were not in a group.");
    }

    /** {@code !groupinfo} — print the caller's group composition. */
    static void cmdGroupInfo(Player pl) {
        int ownerUid = ownerUidOf(pl);
        if (ownerUid == 0) {
            reply(pl, "No character attached.");
            return;
        }
        Integer gid = server.gameserver.team.GroupManager.groupIdOf(ownerUid);
        if (gid == null) {
            reply(pl, "Not in a group.");
            return;
        }
        server.gameserver.team.Group g = server.gameserver.team.GroupManager
                .getGroup(gid);
        if (g == null) {
            reply(pl, "Group #" + gid + " missing.");
            return;
        }
        StringBuilder sb = new StringBuilder("Group #").append(gid)
                .append(" (").append(g.size()).append("/")
                .append(server.gameserver.team.Group.MAX_MEMBERS)
                .append("): ");
        for (int[] entry : g.snapshot()) {
            sb.append("0x").append(Integer.toHexString(entry[0]));
            if (entry[1] == server.gameserver.team.Group.ROLE_LEADER) {
                sb.append("(L)");
            }
            sb.append(' ');
        }
        reply(pl, sb.toString().trim());
    }

    /** Caller's character UID, or 0 if no character is attached. */
    static int ownerUidOf(Player pl) {
        PlayerCharacter pc = pl.getCharacter();
        return (pc == null) ? 0 : pc.getMisc(PlayerCharacter.MISC_ID);
    }

    /** Parse "0x..." as hex, otherwise as decimal. */
    static int parseIntFlex(String s) {
        if (s == null) throw new NumberFormatException("null");
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return (int) Long.parseLong(s.substring(2), 16);
        }
        return Integer.parseInt(s);
    }

    private static void cmdOnline(Player pl) {
        java.util.List<Player> players = PlayerManager.getOnlinePlayers();
        if (players == null || players.isEmpty()) {
            reply(pl, "No players online.");
            return;
        }
        StringBuilder sb = new StringBuilder("Online (" + players.size() + "): ");
        for (Player p : players) {
            if (p.getCharacter() != null) {
                sb.append(p.getCharacter().getName()).append(", ");
            }
        }
        reply(pl, sb.toString());
    }

    private static void cmdHelp(Player pl) {
        reply(pl, "Commands: !pos !warp !hp !heal !kill !damage !god !spawn !online "
                + "!sethp !setpsi !setsta !setsl !setcash !probecash "
                + "!hurtmob !hurtself !mobtick !mobstate "
                + "!buddyadd !buddyrm !buddylist "
                + "!groupcreate !groupinvite !groupleave !groupinfo "
                + "!help");
    }

    /**
     * Set HP to an absolute value and push a signed delta packet so the
     * client reacts immediately (PoolStatusBroadcast also re-syncs at ~1 Hz).
     */
    private static void cmdSetHp(Player pl, String args) {
        if (args.isEmpty()) { reply(pl, "Usage: !sethp <value>"); return; }
        try {
            int target = Integer.parseInt(args.split("\\s+")[0]);
            PlayerCharacter pc = pl.getCharacter();
            int delta = target - pc.getHealth();
            pc.setHealth(target);
            pl.send(new PoolUpdate(pl, PoolUpdate.POOL_HP, delta, pc.getMaxHealth()));
            pl.send(new PoolStatusBroadcast(pl));
            reply(pl, "HP -> " + target + " (delta=" + delta + ", max=" + pc.getMaxHealth() + ")");
        } catch (NumberFormatException e) {
            reply(pl, "Invalid HP value: " + args);
        }
    }

    private static void cmdSetPsi(Player pl, String args) {
        if (args.isEmpty()) { reply(pl, "Usage: !setpsi <value>"); return; }
        try {
            int target = Integer.parseInt(args.split("\\s+")[0]);
            PlayerCharacter pc = pl.getCharacter();
            int delta = target - pc.getPsi();
            pc.setPsi(target);
            pl.send(new PoolUpdate(pl, PoolUpdate.POOL_PSI, delta, pc.getMaxPsi()));
            pl.send(new PoolStatusBroadcast(pl));
            reply(pl, "PSI -> " + target + " (delta=" + delta + ", max=" + pc.getMaxPsi() + ")");
        } catch (NumberFormatException e) {
            reply(pl, "Invalid PSI value: " + args);
        }
    }

    private static void cmdSetSta(Player pl, String args) {
        if (args.isEmpty()) { reply(pl, "Usage: !setsta <value>"); return; }
        try {
            int target = Integer.parseInt(args.split("\\s+")[0]);
            PlayerCharacter pc = pl.getCharacter();
            int delta = target - pc.getStamina();
            pc.setStamina(target);
            pl.send(new PoolUpdate(pl, PoolUpdate.POOL_STA, delta, pc.getMaxStamina()));
            pl.send(new PoolStatusBroadcast(pl));
            reply(pl, "STA -> " + target + " (delta=" + delta + ", max=" + pc.getMaxStamina() + ")");
        } catch (NumberFormatException e) {
            reply(pl, "Invalid STA value: " + args);
        }
    }

    private static void cmdSetSoullight(Player pl, String args) {
        if (args.isEmpty()) { reply(pl, "Usage: !setsl <float 0..100>"); return; }
        try {
            float v = Float.parseFloat(args.split("\\s+")[0]);
            pl.send(new SoullightUpdate(pl, v));
            reply(pl, "Soullight -> " + v + " (0x02->0x1f->0x25 0x1f path)");
        } catch (NumberFormatException e) {
            reply(pl, "Invalid Soullight value: " + args);
        }
    }

    private static void cmdSetCash(Player pl, String args) {
        if (args.isEmpty()) { reply(pl, "Usage: !setcash <value>"); return; }
        try {
            int target = Integer.parseInt(args.split("\\s+")[0]);
            PlayerCharacter pc = pl.getCharacter();
            pc.setCash(target);
            // Retail-traced 2026-04-26: 1f 01 00 25 19 [LE32]×5 unreliable
            // snapshot. Slot 0 = wallet; remaining slots are bank/safe/locker
            // balances that Ceres-J doesn't model yet — send the same value
            // in all five slots so HUD shows a consistent number regardless
            // of which slot the client actually reads for the cash readout.
            // 2026-04-26 retail pcap finding (with HUD-screenshot ground
            // truth from 3 mob kills): authoritative cash carrier is
            //   0x03→0x1f→[01 00 25 13 [txn_id 3B] [cash LE32]]
            // Confirmed against retail packets where the LE32 value
            // exactly matched the new HUD CASH readout.
            pl.send(new CashUpdate(pl, target));
            reply(pl, "Cash -> " + target + " (0x03→0x1f→25 13 reliable).");
        } catch (NumberFormatException e) {
            reply(pl, "Invalid cash value: " + args);
        }
    }

    /**
     * Send the same 0x1f→0x25 19 snapshot but with only ONE slot set to
     * the given value (the others stay at 0). Used to bisect which of
     * the five LE32 fields the HUD actually reads for the cash readout.
     * Usage: !setcashslot &lt;slot 0..4&gt; &lt;value&gt;
     */
    private static void cmdSetCashSlot(Player pl, String args) {
        String[] p = args.split("\\s+");
        if (p.length < 2) {
            reply(pl, "Usage: !setcashslot <slot 0..4> <value>");
            return;
        }
        try {
            int slot = Integer.parseInt(p[0]);
            int value = Integer.parseInt(p[1]);
            if (slot < 0 || slot > 4) {
                reply(pl, "slot must be 0..4");
                return;
            }
            int[] s = new int[5];
            s[slot] = value;
            pl.send(new CashSnapshot(pl, s[0], s[1], s[2], s[3], s[4]));
            reply(pl, "snapshot sent — slot " + slot + " = " + value
                    + ", others = 0");
        } catch (NumberFormatException e) {
            reply(pl, "Invalid args: " + args);
        }
    }

    /**
     * Set a specific subskill index to (lvl, pts) and force CharInfo
     * redelivery via zone change. Confirmed 2026-04-26 to update HUD
     * pool max for HLT (27) / ATL (20) / END (21) / PSU (32).
     * Usage: !setsub <index> <lvl> <pts>
     */
    private static void cmdSetSubskill(Player pl, String args) {
        String[] p = args.split("\\s+");
        if (p.length < 3) {
            reply(pl, "Usage: !setsub <index> <lvl> <pts>  e.g. !setsub 27 200 250 (HLT)");
            return;
        }
        try {
            int idx = Integer.parseInt(p[0]);
            int lvl = Integer.parseInt(p[1]);
            int pts = Integer.parseInt(p[2]);
            PlayerCharacter pc = pl.getCharacter();
            pc.setSubskillLVL(idx, lvl);
            // setSubskillPtsPerLvl getter exists; setter would need to be added.
            // For now, log + warp to force CharInfo redelivery.
            reply(pl, "Subskill[" + idx + "] = (" + lvl + ", " + pts + "). Re-zone for HUD update.");
            pl.send(new ForcedZoning(pl, pl.getMapID()));
        } catch (NumberFormatException e) {
            reply(pl, "Invalid args: " + args);
        }
    }

    /**
     * Set HLT subskill to a target value and force-zone — the canonical
     * server-side path to change displayed max HP. The actual displayed
     * value is then computed as f(lvl, pts) by client tick FUN_007e87d0.
     */
    private static void cmdSetMaxHp(Player pl, String args) {
        if (args.isEmpty()) { reply(pl, "Usage: !setmaxhp <lvl 0..200>"); return; }
        try {
            int lvl = Math.max(0, Math.min(200, Integer.parseInt(args.split("\\s+")[0])));
            PlayerCharacter pc = pl.getCharacter();
            pc.setSubskillLVL(PlayerCharacter.SUBSKILL_HLT, lvl);
            reply(pl, "HLT subskill -> " + lvl + ". Re-zoning to apply.");
            pl.send(new ForcedZoning(pl, pl.getMapID()));
        } catch (NumberFormatException e) {
            reply(pl, "Invalid lvl: " + args);
        }
    }

    /**
     * Send a raw {@code 0x3c} attribute-update packet: <br>
     * {@code !attr3c <tag_hex> <a> <b>} where {@code a},{@code b} are
     * decimal LE32 values and {@code tag_hex} is the attribute byte
     * (0x04=cash, 0x09=HP, 0x01/0x02=unknown). Used to identify what
     * each tag drives on the HUD by setting recognizable values.
     */
    private static void cmdAttr3c(Player pl, String args) {
        String[] p = args.split("\\s+");
        if (p.length < 3) {
            reply(pl, "Usage: !attr3c <tag_hex> <valueA> <valueB>  "
                    + "(e.g. !attr3c 04 999999 999999 for cash)");
            return;
        }
        try {
            int tag = Integer.parseInt(p[0], 16);
            int a = Integer.parseInt(p[1]);
            int b = Integer.parseInt(p[2]);
            pl.send(new AttributeUpdate3c(pl, tag, a, b));
            reply(pl, "0x3c tag=0x" + Integer.toHexString(tag)
                    + " a=" + a + " b=" + b);
        } catch (NumberFormatException e) {
            reply(pl, "Bad args: " + args);
        }
    }

    /**
     * Send the retail-traced 41-byte reliable receipt
     * {@code 0x03→0x1f→01 00 25 13 …}. Two modes:
     * <ul>
     *   <li>{@code !setcashreceipt} — emit the retail bytes verbatim
     *       (so we can see which on-screen number, if any, matches a
     *       known retail value: 530915 / 51847635 / 968225 / 2017617).</li>
     *   <li>{@code !setcashreceipt <value>} — send retail bytes with
     *       ALL FOUR candidate LE32 fields overwritten by {@code value};
     *       if the HUD displays {@code value}, we found the carrier
     *       (we'll then bisect which of the four slots).</li>
     *   <li>{@code !setcashreceipt <slot A|B|C|D> <value>} — substitute
     *       only one slot (for the bisection phase).</li>
     * </ul>
     */
    private static void cmdSetCashReceipt(Player pl, String args) {
        String[] p = args.isEmpty() ? new String[0] : args.split("\\s+");
        try {
            if (p.length == 0) {
                pl.send(new CashReceipt(pl));
                reply(pl, "receipt sent verbatim. expect HUD to read one of "
                        + "530915 / 51847635 / 968225 / 2017617 if 0x13 "
                        + "carries cash.");
                return;
            }
            if (p.length == 1) {
                int v = Integer.parseInt(p[0]);
                pl.send(new CashReceipt(pl, CashReceipt.FIELD_A_OFFSET_530K, v));
                pl.send(new CashReceipt(pl, CashReceipt.FIELD_B_OFFSET_51M, v));
                pl.send(new CashReceipt(pl, CashReceipt.FIELD_C_OFFSET_968K, v));
                pl.send(new CashReceipt(pl, CashReceipt.FIELD_D_OFFSET_2M, v));
                reply(pl, "sent 4 receipts (one per candidate slot) with value "
                        + v + ". HUD should show " + v + " if any slot is cash.");
                return;
            }
            // Two args: slot letter + value
            String slot = p[0].toUpperCase();
            int v = Integer.parseInt(p[1]);
            int off;
            switch (slot) {
                case "A": off = CashReceipt.FIELD_A_OFFSET_530K; break;
                case "B": off = CashReceipt.FIELD_B_OFFSET_51M;  break;
                case "C": off = CashReceipt.FIELD_C_OFFSET_968K; break;
                case "D": off = CashReceipt.FIELD_D_OFFSET_2M;   break;
                default:
                    reply(pl, "slot must be A, B, C, or D");
                    return;
            }
            pl.send(new CashReceipt(pl, off, v));
            reply(pl, "sent receipt with slot " + slot + " = " + v);
        } catch (NumberFormatException e) {
            reply(pl, "Usage: !setcashreceipt [<value> | <A|B|C|D> <value>]");
        }
    }

    /**
     * Send an experimental cash-update packet with a chosen discriminator
     * byte. Used to bisect the unknown NC2 cash sub-opcode.
     * Usage: !probecash <sub_hex> [reliable]
     *   sub_hex   — hex byte to use as the cash discriminator (e.g. 04, 13)
     *   reliable  — if "0" use the 0x02 wrapper, else use 0x03 reliable
     */
    private static void cmdProbeCash(Player pl, String args) {
        String[] p = args.split("\\s+");
        if (p.length < 1 || p[0].isEmpty()) {
            reply(pl, "Usage: !probecash <sub_hex> [r=1|u=0]"); return;
        }
        try {
            int sub = Integer.parseInt(p[0], 16) & 0xFF;
            boolean reliable = p.length < 2 || !"u".equals(p[1]) && !"0".equals(p[1]);
            int target = pl.getCharacter().getCash();
            CashUpdateProbe.send(pl, target, sub, !reliable);
            reply(pl, "Probe cash sent: sub=0x" + Integer.toHexString(sub)
                    + " wrapper=" + (reliable ? "0x03" : "0x02") + " value=" + target);
        } catch (NumberFormatException e) {
            reply(pl, "Bad sub byte: " + p[0]);
        }
    }
}
