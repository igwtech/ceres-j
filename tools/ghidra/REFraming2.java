// REFraming3.java — dump callers of FUN_004b7190 (ClientNetBuffer
// enqueue). The caller that splits a 0x13 datagram into per-sub
// messages sets param_1[0]=_Size — the exact value the client uses
// as the dequeued message size. We need its sublen math.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class REFraming3 extends GhidraScript {

    private static final String[] SEEDS = {
        "004b7190", // ClientNetBuffer enqueue
    };
    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_framing_dump3.txt";

    private DecompInterface decomp;

    @Override
    protected void run() throws Exception {
        decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        Listing listing = currentProgram.getListing();

        Set<Address> toDump = new LinkedHashSet<>();
        for (String hex : SEEDS) {
            Address a = addr(hex);
            toDump.add(a);
            for (Function caller : realCallers(a)) {
                toDump.add(caller.getEntryPoint());
                // one level deeper — the 0x13 sub-splitter's caller
                for (Function cc : realCallers(
                        caller.getEntryPoint())) {
                    toDump.add(cc.getEntryPoint());
                }
            }
        }

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Callers of FUN_004b7190 (ClientNetBuffer "
                    + "enqueue) and their callers");
            pw.println();
            for (Address a : toDump) {
                Function fn = listing.getFunctionAt(a);
                String name = fn != null ? fn.getName()
                        : ("FUN_" + a);
                pw.println("======================================================");
                pw.println("FUNCTION " + name + " @ " + a);
                StringBuilder cs = new StringBuilder();
                if (fn != null) {
                    for (Function c : realCallers(a)) {
                        cs.append(c.getName()).append('@')
                          .append(c.getEntryPoint()).append(' ');
                    }
                }
                pw.println("REAL CALLERS: " + cs);
                pw.println("======================================================");
                if (fn != null) {
                    DecompileResults res = decomp.decompileFunction(
                            fn, 120, new ConsoleTaskMonitor());
                    if (res != null && res.decompileCompleted()) {
                        pw.println(res.getDecompiledFunction()
                                .getC());
                    } else {
                        pw.println("(decompile failed)");
                    }
                } else {
                    pw.println("(no function here)");
                }
                pw.println();
            }
        }
        println("Wrote " + toDump.size() + " functions to "
                + OUT_PATH);
    }

    private Address addr(String hex) {
        return currentProgram.getAddressFactory().getAddress(hex);
    }

    private List<Function> realCallers(Address target) {
        List<Function> out = new ArrayList<>();
        collect(target, out, new LinkedHashSet<>(), 0);
        return out;
    }

    private void collect(Address target, List<Function> out,
            Set<Address> seen, int depth) {
        if (depth > 4 || !seen.add(target)) {
            return;
        }
        ReferenceManager rm =
                currentProgram.getReferenceManager();
        for (Reference ref : rm.getReferencesTo(target)) {
            Function f = currentProgram.getFunctionManager()
                    .getFunctionContaining(ref.getFromAddress());
            if (f == null) {
                continue;
            }
            if (f.isThunk()) {
                collect(f.getEntryPoint(), out, seen, depth + 1);
            } else if (!out.contains(f)) {
                out.add(f);
            }
        }
    }
}
