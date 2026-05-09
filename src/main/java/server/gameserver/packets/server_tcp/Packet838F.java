package server.gameserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

/**
 * Interaction-state-commit acknowledgement (TCP S→C 0x83 0x8f).
 *
 * <p>Retail emits this 7-byte fixed packet immediately after a
 * client-initiated interaction has been validated and is being
 * committed (chair sit, medbed, GenRep teleport, vendor open,
 * trade open, fire/aim PvP, ...). It signals "request accepted —
 * dependent state-change packets are about to arrive". The body
 * is invariant {@code 83 8f 00 00 00 00}: 1,392 retail samples
 * across 17/17 captures all carry the same trailing zeros.
 *
 * <p>Trigger packets observed: client {@code 0x03/0x1f sub=0x17}
 * UseItem (verified), plus PvP-aim and combat-start carriers
 * (decode-only). Each handler that processes a validated
 * interaction must emit this BEFORE its own state-change packets
 * — the client treats it as the UI commit point.
 */
public class Packet838F extends PacketBuilderTCP {
    public Packet838F() {
        write(0x83);
        write(0x8f);
        write(0x00);
        write(0x00);
        write(0x00);
        write(0x00);
        write(0x00);
    }
}
