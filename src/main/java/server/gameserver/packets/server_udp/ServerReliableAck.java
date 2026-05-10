package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * S->C reliable sub-tag {@code 0x09} — server-to-client
 * reliable-ACK channel. Symmetric counterpart to the client's
 * {@code 0x03/0x08 ReliableAck} (verified 2026-05-10 against 35
 * retail samples / 11 captures). Inner body is
 * {@code [ack_seq LE16]} where {@code ack_seq} is the seq of
 * the just-received client-reliable packet the server is
 * acknowledging.
 *
 * <p>Wire layout (sub-packet inside a 0x13 burst):
 *
 * <pre>
 *   [0x03][server_seq LE2][0x09][ack_seq LE2]
 *   ─────  PacketBuilderUDP1303 wrapper  ──┘
 *                                          ↑ this packet writes
 *                                            the trailing 3 bytes
 * </pre>
 *
 * <p>This is distinct from the existing {@code [0x13][...][0x01][seq]}
 * emit in {@code GamePacketReaderUDP.readPacket()} which uses the
 * client-style {@code 0x01} ack-request shape echoed back. Retail
 * uses {@code 0x03/0x09} for the proper server-side ack;
 * implementing it closes a P2 protocol-parity gap.
 *
 * <p>Per {@code reliable_ack_08_decoded} memory: retail emits
 * {@code 0x03/0x09} ~3× per session (only on
 * specific high-importance reliables, not every reliable
 * packet). The exact server-side trigger is unknown — this
 * implementation provides the wire-correct emit; the call-site
 * decides when to fire.
 */
public class ServerReliableAck extends PacketBuilderUDP1303 {

    /**
     * Construct a 0x03/0x09 server-side reliable-ack carrying
     * the client-side seq the server is confirming receipt of.
     *
     * @param pl       the target player session
     * @param ackSeq   the client's reliable seq number being acked
     *                 (LE16 — wraps at 65536)
     */
    public ServerReliableAck(Player pl, int ackSeq) {
        super(pl);
        write(0x09);
        writeShort(ackSeq & 0xFFFF);
    }
}
