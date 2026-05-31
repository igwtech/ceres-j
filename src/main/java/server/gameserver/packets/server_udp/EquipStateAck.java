package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Server-&gt;client toolbelt EQUIP / HOLSTER state-ack.
 *
 * <p>Sent in direct 1:1 response to the client's EquipHolster request
 * ({@code 0x03/0x1f/0x01/0x00/0x1f/&lt;slot&gt;}). Drives the HUD's
 * equipped-weapon / toolbelt-slot indicator.
 *
 * <h3>Wire layout</h3>
 *
 * <p>Inner body (10 bytes) after the reliable {@code 0x03 [seq LE2]}
 * wrapper:
 * <pre>
 *   1f [mapid LE2] 25 13 [txn LE2] 0b [slot] 00
 * </pre>
 *
 * <p>So the full reliable sub-packet on the wire is
 * {@code [03][seq LE2][1f][mapid LE2][25][13][txn LE2][0b][slot][00]}.
 *
 * <p>This rides the SAME {@code 0x25 0x13} transactional state-ack
 * envelope the {@link CashUpdate} wallet packet uses
 * ({@code 25 13 [txn LE2][sub-tag][data]}) — cash's sub-tag is
 * {@code 0x04}; the equipped-slot-state sub-tag is {@code 0x0b}.
 * The data is the commanded slot byte echoed verbatim followed by a
 * pad ({@code [slot:LE16]} effectively).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code mapid LE2} — the acting player's current zone/map id
 *       ({@link Player#getMapID()}). Retail apartment = 1.</li>
 *   <li>{@code txn LE2} — per-player monotonic state-change counter
 *       ({@link server.gameserver.GameServerUDPConnection#nextStateAckTxn()});
 *       the client keys on the low byte incrementing per state-ack.</li>
 *   <li>{@code 0x0b} — equipped-slot-state sub-tag.</li>
 *   <li>{@code slot} — echo of the commanded slot byte: {@code 0x00}
 *       holster, {@code 0x01/0x02/0x04/0x08} toolbelt-slot bitmask,
 *       {@code 0xff} sentinel.</li>
 * </ul>
 *
 * <p>Byte-pinned against retail (1:1 causal, two sessions): C→S
 * {@code 1f 01 00 1f &lt;slot&gt;} → exactly one reliable S→C
 * {@code 1f [mapid] 25 13 [txn] 0b [slot] 00}. The {@code 0x4c}
 * PlayerAction full-weapon report ({@code 1f 01 00 4c 0f 00 03 00})
 * is fire-and-forget — it gets NO reply.
 *
 * @see CashUpdate
 * @see server.gameserver.packets.client_udp.EquipHolster
 */
public class EquipStateAck extends PacketBuilderUDP1303 {

    /** Sub-tag for the equipped-slot-state event in the
     *  {@code 0x25 0x13} transactional family. */
    public static final int TAG_EQUIP_SLOT = 0x0b;

    /**
     * @param pl   acting player (mapid + txn counter sourced from here)
     * @param slot commanded slot byte (echoed verbatim, low 8 bits)
     */
    public EquipStateAck(Player pl, int slot) {
        super(pl);
        write(0x1f);
        writeShort(pl.getMapID());          // mapid LE2
        write(0x25);
        write(0x13);
        int txn = pl.getUdpConnection().nextStateAckTxn();
        write(txn & 0xff);                  // txn LE2
        write((txn >> 8) & 0xff);
        write(TAG_EQUIP_SLOT);              // 0x0b
        write(slot & 0xff);                 // echoed slot
        write(0x00);                        // pad
    }
}
