package server.gameserver.command.commands;

import server.database.accounts.Account;
import server.database.items.Item;
import server.database.items.ItemManager;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.command.CommandContext;
import server.gameserver.command.CommandResult;
import server.gameserver.command.GmCommand;
import server.gameserver.packets.server_udp.AddItem;

/**
 * {@code !giveitem <itemId> [count]} — create an item in the caller's
 * F2 inventory and stream it to the client.
 *
 * <p>Token defaults mirror the legacy {@code ./item} debug path in
 * {@code LocalChat}: a fully-conditioned simple item. {@code count}
 * sets the stack size (default 1).
 */
public final class GiveItemCommand implements GmCommand {

    @Override
    public String name() {
        return "giveitem";
    }

    @Override
    public String[] aliases() {
        return new String[]{"additem", "item"};
    }

    @Override
    public int requiredGmLevel() {
        return Account.GM_GAMEMASTER;
    }

    @Override
    public String usage() {
        return "<itemId> [count]";
    }

    @Override
    public String description() {
        return "Create an item in your inventory.";
    }

    @Override
    public CommandResult execute(CommandContext ctx) {
        Player pl = ctx.player();
        PlayerCharacter pc = ctx.character();
        if (pl == null || pc == null) {
            return CommandResult.error("no character attached");
        }
        int itemId = ctx.intArg(0);
        int count = ctx.intArgOr(1, 1);
        if (itemId <= 0 || itemId > Short.MAX_VALUE) {
            return CommandResult.error("itemId out of range: " + itemId);
        }
        if (count < 1) count = 1;

        short[] tokens = new short[17];
        tokens[Item.TOKENS_CURRCOND]    = 255;
        tokens[Item.TOKENS_MAXCOND]     = 255;
        tokens[Item.TOKENS_DMG]         = 200;
        tokens[Item.TOKENS_FREQUENCY]   = 200;
        tokens[Item.TOKENS_HANDLING]    = 200;
        tokens[Item.TOKENS_RANGE]       = 200;
        tokens[Item.TOKENS_AMMOUSES]    = 3;
        tokens[Item.TOKENS_ITEMSONSTACK] =
                (short) Math.min(count, Short.MAX_VALUE);

        Item it = ItemManager.createItem(
                pc.getContainer(PlayerCharacter.PLAYERCONTAINER_F2),
                -1, itemId, tokens, Item.ITEMFLAG_SIMPLE, 0);
        if (it == null) {
            return CommandResult.error(
                    "Failed to create item " + itemId
                            + " (unknown id or inventory full).");
        }
        pl.send(new AddItem(pl, it));
        return CommandResult.ok("Gave item " + itemId
                + " x" + count + ".");
    }
}
