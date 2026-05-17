package server.gameserver.command.commands;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PlayerPositionUpdate;
import server.gameserver.packets.server_udp.PositionUpdate;

/**
 * Shared S→C self-relocation emitter for the GM teleport commands
 * ({@code tp} same-zone branch, {@code resetpos}).
 *
 * <h3>Why this exact recipe</h3>
 *
 * <p>There is <b>no retail packet capture of an intra-zone GM
 * teleport / GenRep</b> in the project corpus (verified: no
 * {@code strace/}/{@code .pcap} sample tagged tele/genrep/warp, no
 * catalog entry). Per the task-#182 directive, rather than invent the
 * byte form of a hypothetical "intra-zone warp" packet we reuse the
 * <em>world-entry self-position pair the server already emits</em> on
 * the {@code WorldEntryEvent}/{@code ReadyForWorldState} path, which
 * <b>is</b> retail-validated:
 *
 * <ol>
 *   <li>{@link PositionUpdate} — reliable {@code 0x03/0x2c} StartPos.
 *       Carries the player's XYZ as IEEE-754 floats at body offsets
 *       7/11/15 (Y/Z/X). Wire layout verified byte-for-byte against
 *       <b>4 retail pcaps</b> (HANNIBAL, NORMAN, AUGUSTO, DRSTONE3 —
 *       see {@code PositionUpdateByteIdentityTest} and
 *       {@code docs/protocol/packets/udp_s2c_03_2c.md}). This is the
 *       packet that actually moves the client to a coordinate.</li>
 *   <li>{@link PlayerPositionUpdate} — reliable {@code 0x03/0x1b}
 *       variant A (12-byte, marker {@code 0x20}). The position-
 *       authority watchdog echo verified against HANNIBAL + NORMAN
 *       retail samples ({@code docs/protocol/packets/udp_s2c_03_1b.md}).
 *       Without it the client's "Synchronizing" overlay does not
 *       clear after a forced self-reposition.</li>
 * </ol>
 *
 * <p><b>On the task brief's {@code 0x03/0x1b 01 00 00 00 03 [XYZ]}
 * form:</b> that byte signature is described in prose in
 * {@code docs/zone_portal_params.md §7} but is <em>not</em> present in
 * the auto-generated wire catalog (1104 {@code 0x03/0x1b}
 * observations across 14 captures record only variant A {@code 00 00
 * 20} and variant B {@code 00 80 09} — never {@code 01 00 00 00 03}).
 * Emitting that exact form would mean inventing bytes, which the brief
 * explicitly forbids. The {@code 0x2c} StartPos is the
 * coordinate-carrying packet that is byte-pinned to retail, so it is
 * the correct lever for an in-zone warp; the {@code 0x1b} variant A
 * accompanies it exactly as world-entry does.</p>
 *
 * <p>This is identical to the sequence
 * {@code WorldEntryEvent.execute()} streams for {@code PositionUpdate}
 * then {@code PlayerPositionUpdate}, so the client handles a GM
 * teleport with the same code path it already accepts at login.</p>
 */
final class SelfRelocate {

    private SelfRelocate() {}

    /**
     * Emit the retail-validated self-position pair so the client
     * actually warps to {@code pc}'s current
     * {@code MISC_X/Y/Z_COORDINATE} within the current zone.
     *
     * <p>Must be called <em>after</em> the caller has written the
     * destination coordinates into the {@link PlayerCharacter} (the
     * {@code 0x2c} StartPos reads them straight from the misc fields).
     *
     * @param pl session to relocate (must be non-null with a
     *           character attached)
     */
    static void inZone(Player pl) {
        PlayerCharacter pc = pl.getCharacter();
        if (pc == null) {
            return;
        }
        for (server.interfaces.ServerUDPPacket p : build(pl)) {
            pl.send(p);
        }
    }

    /**
     * Build (without sending) the ordered self-relocation packet
     * pair. Exposed package-private so a byte-identity test can pin
     * the exact wire output to the retail-validated forms without a
     * bound UDP socket.
     *
     * @param pl session with destination coords already written
     * @return {@code [PositionUpdate (0x2c), PlayerPositionUpdate
     *         (0x1b)]} in send order, or an empty array if no
     *         character is attached
     */
    static server.interfaces.ServerUDPPacket[] build(Player pl) {
        PlayerCharacter pc = pl.getCharacter();
        if (pc == null) {
            return new server.interfaces.ServerUDPPacket[0];
        }
        int mapId = pl.getMapID();
        // 1. coordinate-carrying StartPos (0x03/0x2c) — verified vs
        //    4 retail pcaps. This is what physically moves the client.
        // 2. position-authority watchdog echo (0x03/0x1b variant A) —
        //    verified vs HANNIBAL+NORMAN; clears the re-sync overlay
        //    after the forced reposition.
        return new server.interfaces.ServerUDPPacket[] {
            new PositionUpdate(pl),
            new PlayerPositionUpdate(pl, pc, mapId),
        };
    }
}
