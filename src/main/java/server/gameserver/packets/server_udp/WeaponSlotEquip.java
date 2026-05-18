package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Weapon-slot equip / holster broadcast — the server's reply to a
 * client toolbelt-key press ({@code 0x03/0x1f/<localId>/0x1f}
 * SlotUse). Tells every client in the zone (including the subject)
 * to draw the indicated toolbelt weapon into the player's hand, or
 * to holster the currently-drawn weapon.
 *
 * <h3>Wire format</h3>
 *
 * <p>Mirrors the established per-entity {@code 0x03/0x1f} state
 * broadcast layout used by {@link SitOnChair} (chair posture,
 * retail-verified in
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}):
 *
 * <pre>
 *   1f &lt;localId LE2&gt; 1f &lt;slot u8&gt;
 * </pre>
 *
 * <p>The tag is {@code 0x1f} = {@code SlotUse} (Equipment slot
 * action — {@code docs/PROTOCOL.md} §"0x1F Game Sub-packets"). The
 * single body byte is the toolbelt slot index the client must draw:
 * {@code 0..0x0a} = a weapon belt slot (the client's
 * {@code FUN_007e67b0} "SRV activate slot: %i" handler accepts
 * slot {@code 0..0x0b}), and the holster sentinel {@code 0xff}
 * (-1) = put away the currently-drawn weapon.
 *
 * <p>This echoes the exact shape of the C→S SlotUse the user
 * captured pressing toolbelt key "1":
 * {@code 03 11 00 | 1f 00 00 1f 00} — op {@code 0x1f},
 * localId LE16 {@code 0x0000}, tag {@code 0x1f}, slot byte
 * {@code 0x00}. The server re-emits the same tag/field order with
 * the subject player's {@code localId} so peers animate the
 * draw/holster (same observer rule as {@link SitOnChair}: the
 * subject is NOT skipped — the captured chair-sit player saw their
 * own {@code localId} in the {@code 0x21} broadcast, so SlotUse
 * follows that convention).
 *
 * <p>{@link PacketBuilderUDP1303} writes the
 * {@code [0x13][ctr][ctr+sk][len][0x03][seq LE2]} reliable frame,
 * so this builder writes only the body that follows the
 * {@code 0x03 seq} pair, starting at {@code 0x1f}.
 *
 * @see server.gameserver.packets.client_udp.ToolbeltSlotUse
 * @see SitOnChair
 */
public class WeaponSlotEquip extends PacketBuilderUDP1303 {

    /** Body byte that holsters (stows) the currently-drawn weapon. */
    public static final int HOLSTER = 0xff;

    /**
     * @param pl           the player the reliable frame is sent to
     *                     (supplies the per-session seq counter).
     * @param subjectMapId map/local id of the player whose weapon
     *                     state changed (as the receiver sees them;
     *                     equals {@code subject.getMapID()}).
     * @param slot         toolbelt slot index to draw
     *                     ({@code 0..0x0a}), or {@link #HOLSTER}
     *                     ({@code 0xff}) to put the weapon away.
     */
    public WeaponSlotEquip(Player pl, int subjectMapId, int slot) {
        super(pl);
        write(0x1f);
        writeShort(subjectMapId);   // localId LE16 (subject player)
        write(0x1f);                // tag 0x1f = SlotUse
        write(slot & 0xFF);         // toolbelt slot, or 0xff = holster
    }
}
