// FindEmoteKillSelf.java — Ghidra headless script (task #187).
//
// Locate how the retail client turns the documented commands
//   /set kill_self 1
//   /set reset_position 1
//   /emote <keyword>
// into wire packets. We find the string definitions, every function
// that references them, and decompile those functions so we can
// byte-pin the C->S packet (if any) each command emits.
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient.exe -noanalysis \
//       -scriptPath <worktree>/tools/ghidra \
//       -postScript FindEmoteKillSelf.java

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

public class FindEmoteKillSelf extends GhidraScript {

    static final String[] NEEDLES = {
        "kill_self", "reset_position", "emote", "afk"
    };

    @Override
    public void run() throws Exception {
        Listing listing = currentProgram.getListing();
        DataIterator di = listing.getDefinedData(true);
        Set<Function> targets = new LinkedHashSet<>();

        while (di.hasNext() && !monitor.isCancelled()) {
            Data d = di.next();
            Object v = d.getValue();
            if (!(v instanceof String)) continue;
            String s = ((String) v).trim();
            boolean hit = false;
            for (String n : NEEDLES) {
                if (s.equals(n)) { hit = true; break; }
            }
            if (!hit) continue;
            Address strAddr = d.getAddress();
            println("STRING \"" + s + "\" @ " + strAddr);
            ReferenceIterator ri =
                currentProgram.getReferenceManager()
                    .getReferencesTo(strAddr);
            while (ri.hasNext()) {
                Reference r = ri.next();
                Function f = listing.getFunctionContaining(
                        r.getFromAddress());
                if (f != null) {
                    println("  ref from " + r.getFromAddress()
                            + " in " + f.getName()
                            + " @ " + f.getEntryPoint());
                    targets.add(f);
                }
            }
        }

        DecompInterface dec = new DecompInterface();
        dec.setOptions(new DecompileOptions());
        dec.openProgram(currentProgram);
        for (Function f : targets) {
            println("==== DECOMPILE " + f.getName()
                    + " @ " + f.getEntryPoint() + " ====");
            DecompileResults res =
                dec.decompileFunction(f, 90, new ConsoleTaskMonitor());
            if (res != null && res.decompileCompleted()) {
                println(res.getDecompiledFunction().getC());
            } else {
                println("  <decompile failed>");
            }
        }
        dec.dispose();
    }
}
