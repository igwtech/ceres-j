package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Authoritative wallet update — drives the HUD {@code CASH:} readout.
 *
 * <p>Wire layout (11 bytes after the {@code 0x03 [seq LE2] 0x1f}
 * reliable wrapper):
 * <pre>
 *   01 00 25 13 [txn LE2] [sub-tag=0x04] [cash LE32]
 * </pre>
 *
 * <p>The {@code 0x25 0x13} sub-opcode is a TRANSACTIONAL-EVENT
 * channel that carries a list of {@code [txn LE2][sub-tag 1B][data]}
 * blocks. Sub-tags observed in retail:
 * <ul>
 *   <li>{@code 0x04} — wallet update, 4-byte LE32 cash payload</li>
 *   <li>{@code 0x0b} — 2-byte payload (event marker)</li>
 *   <li>{@code 0x0e} — 1-byte payload (event marker)</li>
 *   <li>{@code 0x05}, {@code 0x02}, {@code 0x03} — compound multi-event</li>
 * </ul>
 *
 * <p>Reverse-engineered 2026-04-26 from a retail pcap covering three
 * mob kills with HUD-screenshot ground truth:
 * <ul>
 *   <li>kill 2: cash 527,652 → 527,729 — packet bytes
 *       {@code 03 99 01 1f 01 00 25 13 e8 2f 04 71 0d 08 00}; the
 *       LE32 {@code 71 0d 08 00} = 527,729 matches the new HUD value.</li>
 *   <li>kill 3: cash 527,729 → 527,782 — packet bytes
 *       {@code 03 36 02 1f 01 00 25 13 ed 2f 04 a6 0d 08 00}; the
 *       LE32 {@code a6 0d 08 00} = 527,782 matches.</li>
 * </ul>
 *
 * <p>The 41-byte vendor-buy variant of this packet has the same
 * 11-byte cash prefix followed by 30 bytes of item-receipt data
 * (GUID + price + slot info). Earlier slot-bisection tests on the
 * vendor variant substituted into the trailing block, never the
 * cash slot at offset 11 — explaining why those tests were
 * negative despite the format being correct.
 *
 * <p>The {@code txn LE2} field increments per event in retail
 * (e7→e8→e9→ea, +1 per kill). We use a monotonic counter.
 */
public class CashUpdate extends PacketBuilderUDP1303 {

    /** Sub-tag values for the 0x25 0x13 transactional family. */
    public static final int TAG_CASH        = 0x04;
    public static final int TAG_EVENT_0B    = 0x0b;
    public static final int TAG_EVENT_0E    = 0x0e;

    private static int txnCounter = 0x2fe0;

    public CashUpdate(Player pl, int newCash) {
        super(pl);
        write(0x1f);
        write(0x01);
        write(0x00);
        write(0x25);
        write(0x13);
        int txn = nextTxn();
        write(txn & 0xff);
        write((txn >> 8) & 0xff);
        write(TAG_CASH);
        writeInt(newCash);
    }

    private static synchronized int nextTxn() {
        return ++txnCounter & 0xffff;
    }
}
