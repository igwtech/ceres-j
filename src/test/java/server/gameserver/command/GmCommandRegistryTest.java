package server.gameserver.command;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import server.database.accounts.Account;
import server.gameserver.Player;
import server.gameserver.command.commands.CommandPack;

/**
 * Parse / dispatch / permission unit tests for the GM command
 * framework (task #179). No wire, no DB — pure registry behaviour.
 */
public class GmCommandRegistryTest {

    /** A test command that records whether it ran. */
    private static final class Spy implements GmCommand {
        final String name;
        final int level;
        boolean ran = false;
        String[] seenArgs;
        Spy(String name, int level) { this.name = name; this.level = level; }
        public String name() { return name; }
        public int requiredGmLevel() { return level; }
        public String usage() { return "<spy>"; }
        public CommandResult execute(CommandContext ctx) {
            ran = true;
            seenArgs = ctx.rawArgs();
            return CommandResult.ok("ran");
        }
    }

    private static Player playerAtLevel(int gm) {
        Account acc = new Account(1);
        acc.setUsername("u");
        acc.setGmLevel(gm);
        return new Player(acc);
    }

    @Test
    public void tokenizeSplitsAndStripsNul() {
        String[] t = GmCommandRegistry.tokenize("tp\0 1  2\t3");
        assertArrayEquals(new String[]{"tp", "1", "2", "3"}, t);
        assertEquals(0, GmCommandRegistry.tokenize("   ").length);
        assertEquals(0, GmCommandRegistry.tokenize(null).length);
    }

    @Test
    public void registersByNameAndAliases() {
        GmCommandRegistry r = new GmCommandRegistry();
        GmCommand c = new GmCommand() {
            public String name() { return "noclip"; }
            public String[] aliases() { return new String[]{"fly"}; }
            public int requiredGmLevel() { return 0; }
            public String usage() { return ""; }
            public CommandResult execute(CommandContext x) {
                return CommandResult.ok("");
            }
        };
        r.register(c);
        assertSame(c, r.lookup("noclip"));
        assertSame(c, r.lookup("FLY"));
        assertTrue(r.isRegistered("fly"));
        assertNull(r.lookup("nope"));
    }

    @Test(expected = IllegalStateException.class)
    public void duplicateNameRejected() {
        GmCommandRegistry r = new GmCommandRegistry();
        r.register(new Spy("dup", 0));
        r.register(new Spy("dup", 0));
    }

    @Test
    public void dispatchRunsCommandWithArgs() {
        GmCommandRegistry r = new GmCommandRegistry();
        Spy spy = new Spy("foo", Account.GM_PLAYER);
        r.register(spy);
        List<String> replies = new ArrayList<>();
        CommandResult res = r.dispatch(playerAtLevel(0),
                "foo a b c", replies::add);
        assertTrue(spy.ran);
        assertArrayEquals(new String[]{"a", "b", "c"}, spy.seenArgs);
        assertEquals(CommandResult.Status.OK, res.status());
    }

    @Test
    public void unknownCommandIsNotFound() {
        GmCommandRegistry r = new GmCommandRegistry();
        CommandResult res = r.dispatch(playerAtLevel(3),
                "doesnotexist", s -> {});
        assertEquals(CommandResult.Status.NOT_FOUND, res.status());
    }

    @Test
    public void lowGmLevelIsDeniedAndCommandNeverRuns() {
        GmCommandRegistry r = new GmCommandRegistry();
        Spy spy = new Spy("adminonly", Account.GM_ADMIN);
        r.register(spy);
        CommandResult res = r.dispatch(playerAtLevel(Account.GM_MODERATOR),
                "adminonly", s -> {});
        assertEquals(CommandResult.Status.DENIED, res.status());
        assertFalse("command body must not run when denied", spy.ran);
    }

    @Test
    public void highGmLevelIsAllowed() {
        GmCommandRegistry r = new GmCommandRegistry();
        Spy spy = new Spy("adminonly", Account.GM_GAMEMASTER);
        r.register(spy);
        CommandResult res = r.dispatch(playerAtLevel(Account.GM_ADMIN),
                "adminonly", s -> {});
        assertEquals(CommandResult.Status.OK, res.status());
        assertTrue(spy.ran);
    }

    @Test
    public void accessorErrorBecomesBadSyntax() {
        GmCommandRegistry r = new GmCommandRegistry();
        r.register(new GmCommand() {
            public String name() { return "needsarg"; }
            public int requiredGmLevel() { return 0; }
            public String usage() { return "<x>"; }
            public CommandResult execute(CommandContext ctx) {
                ctx.intArg(0); // throws — no args supplied
                return CommandResult.ok("");
            }
        });
        CommandResult res = r.dispatch(playerAtLevel(0),
                "needsarg", s -> {});
        assertEquals(CommandResult.Status.BAD_SYNTAX, res.status());
    }

    @Test
    public void nullAccountIsTreatedAsLevelZero() {
        GmCommandRegistry r = new GmCommandRegistry();
        r.register(new Spy("priv", Account.GM_MODERATOR));
        CommandResult res = r.dispatch(new Player(null),
                "priv", s -> {});
        assertEquals(CommandResult.Status.DENIED, res.status());
    }

    @Test
    public void defaultRegistryHasTheTaskCommandSet() {
        GmCommandRegistry r = new GmCommandRegistry();
        CommandPack.registerAll(r);
        for (String n : new String[]{"noclip", "resetpos", "tp",
                "giveitem", "changeskill", "givemoney", "setgmlevel",
                "help"}) {
            assertTrue("missing command " + n, r.isRegistered(n));
        }
        // alias resolves to same instance
        assertSame(r.lookup("fly"), r.lookup("noclip"));
        assertSame(r.lookup("setsub"), r.lookup("changeskill"));
    }

    @Test
    public void legacyAdminStatusMapsToGmAdmin() {
        Account acc = new Account(2);
        acc.setStatus("admin");
        assertEquals(Account.GM_ADMIN, acc.getGmLevel());
        assertTrue(acc.hasGmLevel(Account.GM_GAMEMASTER));
    }
}
