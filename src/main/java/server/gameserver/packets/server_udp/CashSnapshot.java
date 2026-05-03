package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Wallet/inventory cash snapshot — `1f 01 00 25 19 [LE32]×5`.
 *
 * <p>Sub-opcode {@code 0x19} after the {@code 01 00 25} prefix. Captured
 * once during a retail vendor-buy on 2026-04-26
 * ({@code nc2_strace_RETAIL_CASH_VENDOR_BUY_20260426_094907.log}); the
 * server emitted exactly one such packet at the moment cash deducted.
 * The five LE32 fields are credit-magnitude integers — slot 0 is
 * presumed to be the wallet, remaining four are likely
 * bank/safe/locker balances. Ceres-J only models the wallet today;
 * other slots default to 0.
 */
public class CashSnapshot extends PacketBuilderUDP13 {

    public CashSnapshot(Player pl, int wallet, int field1, int field2,
                        int field3, int field4) {
        super(pl);
        write(0x1f);
        write(0x01);
        write(0x00);
        write(0x25);
        write(0x19);
        writeInt(wallet);
        writeInt(field1);
        writeInt(field2);
        writeInt(field3);
        writeInt(field4);
    }

    public CashSnapshot(Player pl, int wallet) {
        this(pl, wallet, 0, 0, 0, 0);
    }
}
