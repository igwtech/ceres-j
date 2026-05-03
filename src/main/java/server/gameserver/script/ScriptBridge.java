package server.gameserver.script;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import server.tools.Out;

/**
 * Java-side dispatch for {@code SendScriptMsg(cmd, dialogclass, ...)}
 * calls coming from Lua scripts.
 *
 * <p>The 55 RPC commands are documented in
 * {@code docs/protocol/CLIENT_LUA_BRIDGE.md}. Each is registered as
 * a {@link Command} that takes the dialogclass + variable args and
 * returns a single Lua value (or NIL if the command doesn't have a
 * meaningful return).
 *
 * <p>Phase 0 only registers a SAY echo so we can prove the bridge
 * works end-to-end. Subsequent phases (especially Phase 7 mission
 * + dialog) will fill in the rest.
 */
public final class ScriptBridge {

    /** A registered RPC command. {@code dialogclass} is the script's
     *  dialog instance (effectively the NPC handle). Args are the
     *  remaining {@code SendScriptMsg(cmd, dialogclass, …)} parameters
     *  as a Varargs starting at index 1 (so arg(1) = first user arg). */
    @FunctionalInterface
    public interface Command {
        LuaValue invoke(LuaValue dialogclass, Varargs args);
    }

    private final Map<String, Command> commands = new HashMap<>();

    public ScriptBridge() {
        installDefaults();
    }

    public void register(String name, Command cmd) {
        commands.put(Objects.requireNonNull(name), Objects.requireNonNull(cmd));
    }

    /** Register the built-in commands. Phase 0 only ships a SAY
     *  echo + an enddialog acknowledger so the smoke test passes.
     *  Real implementations land in phases 2 (chat), 7 (mission). */
    private void installDefaults() {
        // say(text) — log the NPC's spoken line. Real implementation
        // will route to the player's session and emit a chat packet.
        register("say", (dc, args) -> {
            String text = args.checkjstring(1);
            Out.writeln(Out.Info, "[Lua/say] dialogclass="
                    + dc.tojstring() + " text=" + text);
            return LuaValue.NIL;
        });

        // setanswer(counter, text, resultstate, soundid)
        register("setanswer", (dc, args) -> {
            int counter = args.checkint(1);
            String text = args.checkjstring(2);
            int resultstate = args.checkint(3);
            Out.writeln(Out.Info, "[Lua/setanswer] #" + counter
                    + " '" + text + "' -> state " + resultstate);
            return LuaValue.NIL;
        });

        // enddialog() — signal end of dialog tree.
        register("enddialog", (dc, args) -> {
            Out.writeln(Out.Info, "[Lua/enddialog] dialogclass="
                    + dc.tojstring());
            return LuaValue.NIL;
        });

        // Default no-op for unimplemented commands. Returns NIL.
        // Phase 7 will replace this with full implementations.
    }

    /** Dispatch entry point called by the {@code SendScriptMsg}
     *  registered Lua function. */
    LuaValue dispatch(String cmd, LuaValue dialogclass, Varargs args) {
        Command c = commands.get(cmd);
        if (c == null) {
            Out.writeln(Out.Warning, "[Lua/unimpl] SendScriptMsg('" + cmd
                    + "', dc=" + dialogclass.tojstring()
                    + ", args.narg=" + args.narg() + ")");
            return LuaValue.NIL;
        }
        try {
            return c.invoke(dialogclass, args);
        } catch (Exception e) {
            Out.writeln(Out.Error, "[Lua/" + cmd + "] command threw: "
                    + e.getMessage());
            return LuaValue.NIL;
        }
    }

    /** The Lua-callable function {@code SendScriptMsg}. Calls
     *  {@link #dispatch}. */
    static final class SendScriptMsgFunction extends VarArgFunction {
        private final ScriptBridge bridge;
        SendScriptMsgFunction(ScriptBridge bridge) { this.bridge = bridge; }
        @Override
        public Varargs invoke(Varargs args) {
            // arg1 = command name (string)
            // arg2 = dialogclass
            // arg3+ = command-specific args
            if (args.narg() < 2) return LuaValue.NIL;
            String cmd = args.checkjstring(1);
            LuaValue dc = args.arg(2);
            Varargs rest = args.subargs(3);
            return bridge.dispatch(cmd, dc, rest);
        }
    }
}
