package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * NPC death / kill-credit gate — reliable {@code 0x13 → 0x03 → 0x2d}
 * with the 12-byte body form (15-byte sub-packet including the
 * {@code 03 [seq2]} reliable header).
 *
 * <h3>Why this packet exists</h3>
 *
 * <p>Mob HP and the floating damage numbers are <b>client-predicted</b>
 * (weapon damage-types vs mob resists, both shipped in the client defs);
 * the retail server streams NO per-hit HP. The one authoritative combat
 * signal the server sends is this death event: the client PREDICTS the
 * kill from its local HP estimate, plays a death animation, then WAITS
 * for this packet before committing it. If it never arrives the client
 * cancels the predicted death and the mob "comes back to life" — exactly
 * the "death animation plays, cancels, replays on the final hit" the user
 * observed. So this is the gate, not {@link RemoveWorldItem} (which is the
 * follow-up despawn).
 *
 * <h3>Wire format (retail-pinned 2026-06-13)</h3>
 *
 * <p>Body offsets are relative to the {@code 0x2d} sub-opcode (offset 0),
 * matching {@link server.gameserver.npc.Npc2dRecordDecoder}. This is a new
 * 12-byte form distinct from PING(6)/SHORT(10)/RECORD(55); its
 * record-discriminator at {@code [5]} is {@code 0x01}.
 * <pre>
 *   [0]      2d                  sub-opcode
 *   [1..2]   dead_entity  LE16   the mob that died (its runtime entity id)
 *   [3..4]   00 00               CONSTANT
 *   [5]      01                  form discriminator = kill/credit
 *   [6..7]   killer_entity LE16  the entity credited with the kill
 *   [8..9]   00 00               CONSTANT
 *   [10]     dmg                 HP the killer removed (kill credit / XP /
 *                                loot rights). Full-HP mobs ≈ their max HP.
 *   [11]     00                  CONSTANT
 * </pre>
 *
 * <p>Captured: Norman Gates (killer entity 0x0001) kills "Reggie"
 * (entity 0x012c) — {@code 2d 2c01 0000 01 0100 0000 <dmg> 00}; the mob's
 * 0x2d updates then stop entirely. See memory
 * {@code combat_loop_retail_kill_pinned}.
 *
 * <p><b>Killer-id caveat:</b> the only verified capture is the acting
 * player viewing their own kill, where {@code killer_entity} == their own
 * localId. Ceres uses a single global runtime entity id ({@code mapID})
 * per actor, identical across all recipients, so we emit the killer's
 * {@code mapID} to every nearby player. Whether retail re-frames this id
 * per-recipient is unverified (would need a 2-client kill capture) — do not
 * treat the broadcast form as byte-pinned.
 */
public class NpcKillCredit extends PacketBuilderUDP1303 {

    /** Form discriminator at body offset 5 for the kill-credit form. */
    public static final int DISC_KILL = 0x01;

    /** Kill-credit {@code dmg} is a single byte on the wire. */
    public static final int DMG_MAX = 0xff;

    /**
     * @param recipient     the player this death gate is sent to
     * @param deadEntityId  the dead mob's runtime entity id ({@code mapID})
     * @param killerEntityId the killer's runtime entity id ({@code mapID})
     * @param dmg           HP the killer removed (clamped to a byte)
     */
    public NpcKillCredit(Player recipient, int deadEntityId,
                          int killerEntityId, int dmg) {
        super(recipient);
        writeBody(this, deadEntityId, killerEntityId, dmg);
    }

    /** Write the 12-byte kill-credit body into an open builder so callers
     *  can bundle it into a multi-sub-packet datagram without a separate
     *  UDP round-trip (mirrors {@link NpcDataBroadcast#writeBody}). */
    static void writeBody(PacketBuilderUDP1303 b, int deadEntityId,
                          int killerEntityId, int dmg) {
        b.write(0x2d);                       // [0]    sub-opcode
        b.writeShort(deadEntityId);          // [1..2] dead entity LE16
        b.write(0x00);                       // [3]    CONSTANT
        b.write(0x00);                       // [4]    CONSTANT
        b.write(DISC_KILL);                  // [5]    kill/credit form
        b.writeShort(killerEntityId);        // [6..7] killer entity LE16
        b.write(0x00);                       // [8]    CONSTANT
        b.write(0x00);                       // [9]    CONSTANT
        b.write(clampDmg(dmg));              // [10]   HP removed (kill credit)
        b.write(0x00);                       // [11]   CONSTANT
    }

    /** Clamp the credited damage into the single wire byte. */
    public static int clampDmg(int dmg) {
        if (dmg < 0) return 0;
        return Math.min(dmg, DMG_MAX);
    }
}
