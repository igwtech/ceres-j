package server.gameserver.script;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * Smoke tests for the LuaJ host + bridge. Verifies:
 *
 * 1. The host loads dialogheader.lua without errors.
 * 2. {@code SendScriptMsg} from a Lua script reaches the Java bridge.
 * 3. The sandbox excludes {@code os}, {@code io}, etc.
 */
public class LuaScriptHostTest {

    private LuaScriptHost host;
    private ScriptBridge bridge;
    private List<String> sayLog;

    @Before
    public void setUp() {
        bridge = new ScriptBridge();
        sayLog = new ArrayList<>();
        bridge.register("say", (dc, args) -> {
            sayLog.add(args.checkjstring(1));
            return LuaValue.NIL;
        });
        host = new LuaScriptHost(bridge);
    }

    @Test
    public void loadsDialogHeader() {
        // dialogheader.lua defines SAY/ANSWER/ENDDIALOG on top of
        // SendScriptMsg. Loading it must not throw.
        host.loadResource("/lua/dialogheader.lua", "dialogheader");

        // dialogheader.lua's `state` is initialized via a Lua quirk
        // (`a,b,c,d,e = 0` only assigns to a) — state is nil at load
        // time. Set it explicitly via a runner script.
        host.load("setState",
                "function setState(s) state = s; node = 0 end");
        host.call("setState", 0);

        // (node==state) is now (0==0)=true. SAY fires.
        host.call("SAY", "hello world");
        assertEquals(1, sayLog.size());
        assertEquals("hello world", sayLog.get(0));

        // NODE(1) sets node=1, so (node==state)=(1==0)=false; SAY no-op.
        host.call("NODE", 1);
        host.call("SAY", "should-not-fire");
        assertEquals(1, sayLog.size());

        // NODE(0) brings us back; SAY fires again.
        host.call("NODE", 0);
        host.call("SAY", "second line");
        assertEquals(2, sayLog.size());
        assertEquals("second line", sayLog.get(1));
    }

    @Test
    public void sandboxExcludesOsAndIo() {
        host.load("test", "return type(os) .. ',' .. type(io) .. ',' .. type(loadfile)");
        LuaValue result = host.call("test"); // not actually a function, just runs
        // The script body is loaded and executed at load() time. Re-run by
        // load + call patterns are split; for this smoke test we'll instead
        // load a script that returns its check via a side effect.
        host.load("checkSandbox",
                "function checkSandbox() return tostring(os) .. '|' "
                + "  .. tostring(io) .. '|' .. tostring(loadfile) end");
        LuaValue out = host.call("checkSandbox");
        String s = out.tojstring();
        // All three should be 'nil' since we wiped them.
        assertTrue("expected os/io/loadfile to be nil, got: " + s,
                s.equals("nil|nil|nil"));
    }

    @Test
    public void sendScriptMsgReachesBridge() {
        // A toy script that calls SendScriptMsg directly.
        host.load("toy",
                "function trigger() SendScriptMsg('say', 0, 'from-lua') end");
        host.call("trigger");
        assertEquals(1, sayLog.size());
        assertEquals("from-lua", sayLog.get(0));
    }

    @Test
    public void unknownCommandLogsButDoesntThrow() {
        host.load("toy",
                "function bad() SendScriptMsg('not_a_real_cmd', 0, 'x') end");
        // Should log a warning but not throw.
        host.call("bad");
        // No say events produced.
        assertEquals(0, sayLog.size());
    }
}
