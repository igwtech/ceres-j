// FindEmoteWire.java — Ghidra headless (task #187).
// Find refs to the chat command strings "/emote ", "/e ", "/me "
// and decompile the chat-input dispatcher + the packet send helper
// FUN_006576f0 (used by the kill_self block of FUN_0065d710).

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.util.task.ConsoleTaskMonitor;

import java.util.LinkedHashSet;
import java.util.Set;

public class FindEmoteWire extends GhidraScript {

    static final String[] NEEDLES = { "/emote ", "/e ", "/me " };
    // helpers worth seeing in full
    static final String[] FUNCS = { "FUN_006576f0", "FUN_007931c0" };

    @Override
    public void run() throws Exception {
        Listing listing = currentProgram.getListing();
        DataIterator di = listing.getDefinedData(true);
        Set<Function> targets = new LinkedHashSet<>();

        while (di.hasNext() && !monitor.isCancelled()) {
            Data d = di.next();
            Object v = d.getValue();
            if (!(v instanceof String)) continue;
            String s = (String) v;
            boolean hit = false;
            for (String n : NEEDLES) if (s.equals(n)) hit = true;
            if (!hit) continue;
            Address a = d.getAddress();
            println("STRING " + javaEsc(s) + " @ " + a);
            ReferenceIterator ri =
                currentProgram.getReferenceManager().getReferencesTo(a);
            while (ri.hasNext()) {
                Reference r = ri.next();
                Function f = listing.getFunctionContaining(
                        r.getFromAddress());
                if (f != null) {
                    println("  ref @ " + r.getFromAddress()
                            + " in " + f.getName());
                    targets.add(f);
                }
            }
        }

        for (String fn : FUNCS) {
            Function f = getGlobalFunctions(fn).isEmpty()
                    ? null : getGlobalFunctions(fn).get(0);
            if (f != null) targets.add(f);
        }

        DecompInterface dec = new DecompInterface();
        dec.setOptions(new DecompileOptions());
        dec.openProgram(currentProgram);
        for (Function f : targets) {
            println("==== DECOMPILE " + f.getName()
                    + " @ " + f.getEntryPoint() + " ====");
            DecompileResults res =
                dec.decompileFunction(f, 90, new ConsoleTaskMonitor());
            if (res != null && res.decompileCompleted())
                println(res.getDecompiledFunction().getC());
            else
                println("  <decompile failed>");
        }
        dec.dispose();
    }

    static String javaEsc(String s) {
        return "\"" + s.replace("\n", "\\n") + "\"";
    }
}
