package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1302;

/**
 * Runtime Soullight update via the {@code 0x02 → 0x1f → 0x01 0x00 0x25 0x1f}
 * path — same wire layout as {@link InitSoullight02} but invokable at any
 * point during the session, not only world entry.
 *
 * <p>Wire layout (verified against retail pcap, see PACKET_REFERENCE.md
 * §6.2 and PROTOCOL.md "0x02 wrapper" entry):
 * <pre>
 *   0x02 [seq LE2] 0x1f 0x01 0x00 0x25 0x1f [soullight_float LE4]
 * </pre>
 *
 * <p>Soullight is encoded as an IEEE-754 float in [0.0, 100.0]; 100.0 is
 * pure good, 0.0 is most evil. The client's `FULLCHARSYSTEM` then maps
 * this onto a 0..255 byte for HUD display via
 * {@code byte = clamp(sqrt(|signed|) * 0.1 + 128, 0, 255)} (see Ghidra
 * decompile of FUN_007e86b0 at neocronclient.exe).
 */
public class SoullightUpdate extends PacketBuilderUDP1302 {
    public SoullightUpdate(Player pl, float soullight) {
        super(pl);
        write(0x1f);   // GamePackets sub-type
        write(0x01);   // variant
        write(0x00);
        write(0x25);   // Player/Char namespace
        write(0x1f);   // Soullight sub-opcode
        writeFloat(soullight);
    }
}
