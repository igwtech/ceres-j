package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Universal attribute-update packet with outer sub-type {@code 0x3c}.
 *
 * <p>Wire layout (12 bytes total inside the 0x13 gamedata wrapper):
 * <pre>
 *   3c 01 00 [TAG] [LE32 valueA] [LE32 valueB]
 * </pre>
 *
 * <p>Reverse-engineered from a retail pcap capture 2026-04-26 — this
 * channel was previously invisible because (a) {@code parse-burst.py}
 * didn't list {@code 0x3c} as a known outer sub-type, and (b) the
 * earlier pcap-less analysis had no way to compare strace UDP coverage
 * against the wire. Confirmed tags so far:
 * <ul>
 *   <li>{@code 0x04} — CASH. The two LE32s appear to be wallet
 *       balances before and after the transaction (during a vendor
 *       buy, the values differ by the item's markup).</li>
 *   <li>{@code 0x09} — HP / damage delta. Fired at the moment of fall
 *       impact in the same retail capture.</li>
 *   <li>{@code 0x01}, {@code 0x02} — unidentified, both observed in
 *       idle traffic with credit-magnitude integer values.</li>
 * </ul>
 */
public class AttributeUpdate3c extends PacketBuilderUDP13 {

    public static final int TAG_UNKNOWN_01 = 0x01;
    public static final int TAG_UNKNOWN_02 = 0x02;
    public static final int TAG_CASH       = 0x04;
    public static final int TAG_HP_DAMAGE  = 0x09;

    public AttributeUpdate3c(Player pl, int tag, int valueA, int valueB) {
        super(pl);
        write(0x3c);
        write(0x01);
        write(0x00);
        write(tag & 0xFF);
        writeInt(valueA);
        writeInt(valueB);
    }

    /** Convenience: cash with both fields set to the new wallet value. */
    public static AttributeUpdate3c cash(Player pl, int newWallet) {
        return new AttributeUpdate3c(pl, TAG_CASH, newWallet, newWallet);
    }
}
