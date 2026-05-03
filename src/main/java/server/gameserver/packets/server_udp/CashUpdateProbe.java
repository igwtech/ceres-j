package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1302;
import server.networktools.PacketBuilderUDP1303;

/**
 * Experimental cash-update packet used to bisect the unknown NC2 cash
 * sub-opcode by analogy with Soullight (`0x02 → 0x1f → 0x01 0x00 0x25
 * 0x1f <float>`) and the NC1 form (`0x03 → 0x1f → [localID] 0x25 →
 * 0x13 → [trans] 0x04 → <credits LE4>`).
 *
 * <p>Used from {@code !probecash <hex> [r|u]} to swap the discriminator
 * byte that follows {@code 0x25} and the wrapper (0x02 simplified vs
 * 0x03 reliable) until the client visibly updates the credits HUD.
 */
public final class CashUpdateProbe {

    private CashUpdateProbe() {}

    /** Send a probe with the given discriminator + wrapper choice. */
    public static void send(Player pl, int credits, int sub, boolean simplifiedWrapper) {
        if (simplifiedWrapper) {
            pl.send(new Variant02(pl, credits, sub));
        } else {
            pl.send(new Variant03(pl, credits, sub));
        }
    }

    /** {@code 0x02 → 0x1f → 0x01 0x00 0x25 [sub] [credits LE4]} */
    private static final class Variant02 extends PacketBuilderUDP1302 {
        Variant02(Player pl, int credits, int sub) {
            super(pl);
            write(0x1f);
            write(0x01);
            write(0x00);
            write(0x25);
            write(sub & 0xFF);
            writeInt(credits);
        }
    }

    /** NC1-style reliable: {@code 0x03 → 0x1f → [localID LE2] 0x25 [sub] [trans LE2] 0x04 [credits LE4]} */
    private static final class Variant03 extends PacketBuilderUDP1303 {
        Variant03(Player pl, int credits, int sub) {
            super(pl);
            write(0x1f);
            writeShort(pl.getCharacter().getMisc(PlayerCharacter.MISC_ID));
            write(0x25);
            write(sub & 0xFF);
            writeShort(pl.getTransactionID());
            write(0x04);
            writeInt(credits);
        }
    }
}
