package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Chair stand-up (exit-seat) broadcast — sent when a seated player
 * leaves a chair (explicit C→S {@code 0x03/0x1f/0x22} exit request,
 * or implicitly when they move/re-use). Clears the seated posture
 * for every client in the zone and re-anchors the player at a stand
 * position.
 *
 * <h3>Wire format (retail-verified)</h3>
 *
 * <p>Byte-pinned from {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}.
 * At t=44.177s the captured player sent C→S {@code 03 <seq> 1f 03 00 22}
 * (exit-seat, no body); the server replied at t=44.560s with a
 * reliable {@code 0x03/0x1f} sub-packet:
 *
 * <pre>
 *   03 &lt;seq LE2&gt; 1f &lt;localId LE2&gt; 22
 *      &lt;Y+768 LE2&gt; &lt;Z+768 LE2&gt; &lt;X+768 LE2&gt; &lt;UD u8&gt; &lt;LR u8&gt; &lt;Act u8&gt;
 * </pre>
 *
 * <p>Captured inner body (post {@code 03 seq2}):
 * {@code 1f 03 00 22 42 79 31 7f c4 89 80 80 69} — localId
 * {@code 0x0003}, tag {@code 0x22}, three packed {@code u16 LE}
 * coordinate words and three orientation bytes. This is the NC2
 * spelling of the NC1 {@code PMsgBuilder::BuildCharExitSeatMsg}
 * ({@code tinns/.../MessageBuilder.cxx:535}); identical tag (0x22)
 * and field order.
 *
 * <p><strong>Coordinate frame note.</strong> The NC1/NC2 exit-seat
 * frame packs each axis as {@code u16 (coord + 768)}. The NCE 2.5
 * ("Evolution") server stores MISC coordinates as world units
 * (float32-sourced; see {@link server.gameserver.packets.client_udp.Movement}).
 * We emit {@code (worldUnit + 768) & 0xFFFF} per axis so the wire
 * <em>structure</em> matches retail exactly; the absolute stand
 * position is approximate because retail's exact seat→stand
 * coordinate-space mapping is not derivable from this single
 * capture. In practice the stand-up is movement-driven (the client
 * resumes {@code 0x20} movement, which already drives the peer
 * {@link SMovement} broadcast), so this packet primarily clears the
 * seated <em>posture</em> flag on peers.
 *
 * @see SitOnChair
 */
public class ExitSeat extends PacketBuilderUDP1303 {

    /**
     * @param pl           the player the reliable frame is sent to.
     * @param subjectMapId map/local id of the player who stood up.
     * @param pc           the standing player's character (supplies
     *                     the stand coordinates / orientation).
     */
    public ExitSeat(Player pl, int subjectMapId, PlayerCharacter pc) {
        super(pl);
        write(0x1f);
        writeShort(subjectMapId);   // localId LE16 (subject player)
        write(0x22);
        writeShort((pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE)
                + 768) & 0xFFFF);
        writeShort((pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE)
                + 768) & 0xFFFF);
        writeShort((pc.getMisc(PlayerCharacter.MISC_X_COORDINATE)
                + 768) & 0xFFFF);
        write(pc.getMisc(PlayerCharacter.MISC_TILT) & 0xFF);    // UD
        write(pc.getMisc(PlayerCharacter.MISC_ORIENTATION)
                & 0xFF);                                         // LR
        write(pc.getMisc(PlayerCharacter.MISC_STATUS) & 0xFF);  // Act
    }
}
