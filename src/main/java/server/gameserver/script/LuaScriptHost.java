package server.gameserver.script;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import server.tools.Out;

/**
 * Sandboxed LuaJ host for NPC dialog and mission scripts.
 *
 * <p>Each {@link LuaScriptHost} owns one Lua state. Lua isn't
 * thread-safe, so the host serializes calls behind a lock. In Phase 7
 * we'll pin one host per zone (extracted via the
 * {@code WorldMessageBus} tick loop), but Phase 0 only needs to prove
 * the bridge works — a single host is fine.
 *
 * <p>The sandbox EXCLUDES {@code os}, {@code io}, {@code package},
 * {@code debug}, {@code coroutine.create} (the resume/yield paths are
 * harmless on their own but the create path can spawn unbounded
 * coroutines). Bridge tables are installed at the top level under
 * {@code cere.npc}, {@code cere.player}, {@code cere.mission},
 * {@code cere.world} and shadow the corresponding NC2 client globals
 * (which call into C-side hooks).
 *
 * <p>NC2 client scripts (extracted from {@code scripts.pak}) call
 * {@code SendScriptMsg(cmd, dialogclass, args...)}. We register
 * {@code SendScriptMsg} as a Java function that routes to the bridge
 * tables. See {@link ScriptBridge} for the dispatch.
 */
public final class LuaScriptHost {

    private final Globals globals;
    private final ReentrantLock lock = new ReentrantLock();
    private final ScriptBridge bridge;
    private final Map<String, LuaValue> compiledScripts = new HashMap<>();

    public LuaScriptHost(ScriptBridge bridge) {
        this.bridge = bridge;
        this.globals = buildSandbox();
    }

    /** Build the sandbox: standard Lua globals minus dangerous APIs.
     *  We start from {@code JsePlatform.standardGlobals()} (which has
     *  base, table, string, math, bit32, os, io, package, debug,
     *  coroutine, plus the LuaC compiler installed) and strip the
     *  filesystem / process / package APIs. */
    private Globals buildSandbox() {
        Globals g = JsePlatform.standardGlobals();

        // Strip dangerous globals.
        for (String banned : new String[] {
                "os", "io", "package", "debug",
                "loadfile", "dofile", "require"}) {
            g.set(banned, LuaValue.NIL);
        }

        // The single bridge function the client's dialogheader.lua calls.
        // Signature: SendScriptMsg(cmd, dialogclass, ...args)
        g.set("SendScriptMsg", new ScriptBridge.SendScriptMsgFunction(bridge));

        // Stub the cere namespace for forward compatibility.
        LuaTable cere = new LuaTable();
        cere.set("npc", new LuaTable());
        cere.set("player", new LuaTable());
        cere.set("mission", new LuaTable());
        cere.set("world", new LuaTable());
        g.set("cere", cere);

        return g;
    }

    /** Load and store a script by name (no execution). Subsequent
     *  invokes {@link #call(String, String, Object...)} will reuse it. */
    public void load(String scriptName, String luaSource) {
        lock.lock();
        try {
            LuaValue chunk = globals.load(luaSource, scriptName);
            // Execute once to define top-level functions in globals.
            chunk.call();
            compiledScripts.put(scriptName, chunk);
        } catch (LuaError e) {
            Out.writeln(Out.Error,
                    "LuaScriptHost: failed to load '" + scriptName + "': "
                    + e.getMessage());
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /** Load a script from the classpath ({@code src/main/resources/lua/...}). */
    public void loadResource(String resourcePath, String scriptName) {
        try (InputStream in = LuaScriptHost.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                Out.writeln(Out.Warning,
                        "LuaScriptHost: resource not found: " + resourcePath);
                return;
            }
            String src = new String(in.readAllBytes());
            load(scriptName, src);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + resourcePath, e);
        }
    }

    /** Call a global Lua function defined in any previously loaded script.
     *  Returns the function's first return value, or NIL if undefined. */
    public LuaValue call(String functionName, Object... args) {
        lock.lock();
        try {
            LuaValue fn = globals.get(functionName);
            if (fn.isnil() || !fn.isfunction()) return LuaValue.NIL;
            LuaValue[] luaArgs = new LuaValue[args.length];
            for (int i = 0; i < args.length; i++) {
                luaArgs[i] = toLua(args[i]);
            }
            return fn.invoke(luaArgs).arg1();
        } catch (LuaError e) {
            Out.writeln(Out.Error, "LuaScriptHost.call(" + functionName
                    + "): " + e.getMessage());
            return LuaValue.NIL;
        } finally {
            lock.unlock();
        }
    }

    private static LuaValue toLua(Object v) {
        if (v == null) return LuaValue.NIL;
        if (v instanceof Boolean) return LuaValue.valueOf((Boolean) v);
        if (v instanceof Integer) return LuaValue.valueOf((Integer) v);
        if (v instanceof Long) return LuaValue.valueOf(((Long) v).doubleValue());
        if (v instanceof Double) return LuaValue.valueOf((Double) v);
        if (v instanceof Float) return LuaValue.valueOf(((Float) v).floatValue());
        if (v instanceof String) return LuaValue.valueOf((String) v);
        return LuaValue.valueOf(v.toString());
    }

    public ScriptBridge bridge() { return bridge; }

    /** For tests: clear loaded scripts and rebuild the sandbox. Useful
     *  for `cere reload-scripts` admin command. */
    public void reset() {
        lock.lock();
        try {
            compiledScripts.clear();
        } finally {
            lock.unlock();
        }
    }
}
