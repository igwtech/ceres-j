package server.gameserver.packets.client_udp;

import server.gameserver.GameServer;
import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.npc.MobDamageIntent;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.WorldMessageBus;
import server.tools.Out;

/**
 * Client→server weapon-fire request
 * ({@code 0x03/0x1f/<localId>/0x01}). Sent each time the player's drawn
 * weapon fires a shot at the entity under the crosshair.
 *
 * <h3>Wire format (retail-pinned 2026-06-13)</h3>
 *
 * <p>After the reliable envelope {@code 03 [seq2] 1f [localId LE2]} the
 * inner body is:
 * <pre>
 *   01 [weapon_id LE2] [target_id LE2] [8B shot data] [shot_coord f32]
 * </pre>
 * <ul>
 *   <li>{@code weapon_id} = {@code defs.weapons.id} of the drawn weapon
 *       (e.g. {@code 0x0122} = Lazar Gun).</li>
 *   <li>{@code target_id} = the runtime entity id the client's aim RAY
 *       hit — an NPC/mob when aimed at one, or a world hit-point
 *       (e.g. {@code 0x03fe}) when aimed at terrain. Terrain ids never
 *       resolve to an NPC, so they're harmlessly ignored.</li>
 *   <li>{@code shot_coord} climbs monotonically (shooter drift) — not
 *       needed server-side.</li>
 * </ul>
 *
 * <h3>What the server does — and does NOT do</h3>
 *
 * <p>Mob HP and the floating damage numbers ("35+8" laser+burn) are
 * <b>client-predicted</b> from the weapon's damage-types vs the mob's
 * resists (both ship in the client defs); the retail server streams NO
 * per-hit HP (verified: {@code recvfrom} is the only recv path and it
 * carries none). The server's job is purely authoritative bookkeeping:
 * deplete the mob's server-side HP and, when it reaches 0, emit the
 * death / kill-credit event (the 15-byte {@code 0x03/0x2d}) + despawn —
 * the gate the client waits for before committing the death animation.
 * See memory {@code combat_loop_retail_kill_pinned}.
 *
 * @see server.gameserver.npc.MobDamageIntent
 * @see server.gameserver.npc.MobDeathHandler
 */
public final class WeaponFire extends GamePacketDecoderUDP {

    /** Server-side per-shot damage. The client predicts the HP bar and
     *  the floating multi-type damage numbers itself (weapon damage-types
     *  vs mob resists, both shipped in the client defs); the server only
     *  needs to deplete the mob's authoritative HP at a plausible rate so
     *  the death gate ({@link server.gameserver.packets.server_udp.NpcKillCredit})
     *  fires after a believable number of shots. Exact server-side weapon
     *  damage modelling (per-weapon, per-resist) is a follow-up — there is
     *  no verified {@code defs.weapons → server damage} accessor yet, and
     *  inventing one would not change what the client renders.
     *  (Package-visible for tests.) */
    static final int PER_SHOT_DAMAGE = 40;

    public WeaponFire(byte[] subPacket) {
        super(subPacket);
    }

    /** weapon_id LE16 at body offset 7 ({@code 03 [seq2] 1f [localId] 01
     *  [weapon LE2] ...}). Exposed for tests. */
    public int parseWeaponId() {
        reset();
        skip(7);
        int lo = read() & 0xff, hi = read() & 0xff;
        return lo | (hi << 8);
    }

    /** target_id LE16 at body offset 9. Exposed for tests. */
    public int parseTargetId() {
        reset();
        skip(9);
        int lo = read() & 0xff, hi = read() & 0xff;
        return lo | (hi << 8);
    }

    @Override
    public void execute(Player pl) {
        int weaponId = parseWeaponId();
        int targetId = parseTargetId();

        Zone z = pl.getZone();
        if (z == null) return;
        NPC target = z.getNPC(targetId);
        if (target == null) {
            // Terrain / non-mob hit (e.g. the floor) — nothing to damage.
            return;
        }
        if (target.isDead()) return;

        int attackerUid = pl.getCharacter().getMisc(
                server.database.playerCharacters.PlayerCharacter.MISC_ID);
        WorldMessageBus bus = GameServer.getBus();
        if (bus != null) {
            // Reuse the existing damage→death pipeline: MobManager
            // depletes HP and, on HP<=0, posts MobDeathIntent which
            // MobDeathHandler turns into the death gate + despawn.
            bus.post(new MobDamageIntent(targetId, z.getZoneId(),
                    attackerUid, PER_SHOT_DAMAGE));
        }
        Out.writeln(Out.Info,
            "WeaponFire: " + pl.getCharacter().getName() + " weapon=0x"
                + Integer.toHexString(weaponId) + " -> mob 0x"
                + Integer.toHexString(targetId) + " dmg=" + PER_SHOT_DAMAGE
                + " (hp " + target.getHP() + "/" + target.getMaxHP() + ")");
    }
}
