package server.gameserver.command.commands;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.command.CommandContext;
import server.gameserver.command.CommandResult;
import server.gameserver.command.GmCommand;
import server.gameserver.packets.server_udp.CashUpdate;

/**
 * {@code !givemoney <amount>} — add (or, with a negative amount,
 * subtract) cash and push the verified wallet-update packet.
 *
 * <p>Uses the retail-verified cash carrier
 * ({@code 0x03→0x1f→[01 00 25 13 [txn LE2][0x04][cash LE32]]} — see
 * {@link CashUpdate} and project memory
 * {@code cash_and_falldamage_subops}). The new absolute balance is
 * persisted on the character and sent to the HUD.
 */
public final class GiveMoneyCommand implements GmCommand {

    @Override
    public String name() {
        return "givemoney";
    }

    @Override
    public String[] aliases() {
        return new String[]{"money", "givecash"};
    }

    @Override
    public int requiredGmLevel() {
        return Account.GM_GAMEMASTER;
    }

    @Override
    public String usage() {
        return "<amount>  (negative subtracts)";
    }

    @Override
    public String description() {
        return "Add or remove cash (verified CashUpdate path).";
    }

    @Override
    public CommandResult execute(CommandContext ctx) {
        Player pl = ctx.player();
        PlayerCharacter pc = ctx.character();
        if (pl == null || pc == null) {
            return CommandResult.error("no character attached");
        }
        int delta = ctx.intArg(0);
        long balance = (long) pc.getCash() + delta;
        if (balance < 0) balance = 0;
        if (balance > Integer.MAX_VALUE) balance = Integer.MAX_VALUE;
        int newCash = (int) balance;
        pc.setCash(newCash);
        pl.send(new CashUpdate(pl, newCash));
        return CommandResult.ok((delta >= 0 ? "Added " : "Removed ")
                + Math.abs(delta) + " — new balance " + newCash + ".");
    }
}
