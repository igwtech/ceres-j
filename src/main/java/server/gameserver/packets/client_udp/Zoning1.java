package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.inventory.PlayerInventory;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Packet830D;
import server.tools.Out;

/**
 * Client zone-edge crossing notification (reliable {@code 0x03/0x22}
 * sub {@code 0x0d}). Modern NCE 2.5.x client fires this when the
 * player walks into a BSP zone-trigger volume; the destination
 * zone_id is in the body.
 *
 * <h3>Retail response (verified from
 * {@code docs/zoning_protocol_2026-05-02.md})</h3>
 *
 * <p>Modern retail responds via the TCP channel:
 *
 * <pre>
 *   S->C  TCP   0x83 0x0d  4B   GameinfoReady — {@code 83 0d 00 00}
 *   S->C  TCP   0x83 0x0c       Location — opcode + zone_id LE32 +
 *                                8 zero bytes + null-terminated
 *                                BSP path
 * </pre>
 *
 * <p>The client keeps its world state across transitions —
 * coordinates, CharInfo, inventory all retained. Only the BSP swap
 * happens locally. <strong>No UDP ForcedZoning, no Synchronizing
 * splash.</strong>
 *
 * <p>The legacy {@code SZoning1} UDP path (which this handler used
 * to call) triggered the persistent "Synchronizing" overlay seen in
 * live testing on the modern NCE client. Removed in favour of the
 * verified TCP flow.
 */
public class Zoning1 extends GamePacketDecoderUDP {

    public Zoning1(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        // Body layout from live capture:
        //   skip 14 bytes of header, then [location LE32][i LE32]
        // The trailing "i" was the legacy SZoning1's session id.
        // Reading it preserves the wire-offset compatibility with
        // any future fall-back path.
        skip(14);
        int newLocation = readInt();
        readInt(); // legacy session id, unused under the TCP flow

        pl.getCharacter().setMisc(PlayerCharacter.MISC_LOCATION, newLocation);
        pl.updateZone();
        ((PlayerInventory) pl.getCharacter().getContainer(
                PlayerCharacter.PLAYERCONTAINER_F2)).doSort();

        // Retail TCP zone-swap pair. Order matters: GameinfoReady
        // first (signals "you're about to load a new BSP"), Location
        // second (carries the BSP path).
        if (pl.getTcpConnection() != null) {
            pl.send(new Packet830D());
            pl.send(new Location(pl));
        } else {
            Out.writeln(Out.Warning,
                "Zoning1: no TCP connection for player "
                + (pl.getCharacter() == null ? "?"
                                              : pl.getCharacter().getName())
                + " — zone swap dropped");
        }
    }
}
