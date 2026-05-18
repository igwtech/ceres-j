// REStateSync2.java — trace real callers of the WWORLDMGR dispatcher and
// the WA-spawn factory that builds SCRIPTEDPLAYER from the Type-15/Type-30 stream.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

public class REStateSync2 extends GhidraScript {

    // Resolve non-thunk callers transitively (skip jump-table thunks) for these.
    private static final String[] TRACE_CALLERS = {
        "00541f20",  // WWORLDMGR dispatcher  — who maps opcode->Type byte
        "004d8a50",  // WA spawn factory (called from FUN_00540ab0)
    };

    // Direct decompile targets (the WA factory + helpers).
    private static final String[] ADDRS = {
        "004d8a50",  // WA spawn factory
        "004d8b30",  // pre-spawn helper
        "0053ffb0",  // post-spawn helper
        "004e1f10",  // entity hash lookup (used by FUN_005412d0)
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump2.txt";

    private DecompInterface decomp;
    private Listing listing;

    @Override
    protected void run() throws Exception {
        decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            for (String hex : TRACE_CALLERS) {
                Address a = currentProgram.getAddressFactory().getAddress(hex);
                pw.println("##### NON-THUNK CALLERS OF " + hex + " #####");
                Set<String> seen = new LinkedHashSet<>();
                ArrayDeque<Address> work = new ArrayDeque<>();
                work.add(a);
                Set<String> visited = new LinkedHashSet<>();
                while (!work.isEmpty()) {
                    Address cur = work.poll();
                    if (!visited.add(cur.toString())) continue;
                    ReferenceIterator ri = currentProgram.getReferenceManager()
                            .getReferencesTo(cur);
                    while (ri.hasNext()) {
                        Reference r = ri.next();
                        Function cf = listing.getFunctionContaining(r.getFromAddress());
                        if (cf == null) continue;
                        String nm = cf.getName();
                        if (nm.startsWith("thunk_")) {
                            // climb past the thunk
                            work.add(cf.getEntryPoint());
                        } else {
                            seen.add(nm + " @ " + cf.getEntryPoint()
                                     + "  (callsite " + r.getFromAddress() + ")");
                        }
                    }
                }
                for (String s : seen) pw.println("  " + s);
                pw.println();
            }

            for (String hex : ADDRS) {
                Address addr = currentProgram.getAddressFactory().getAddress(hex);
                Function fn = listing.getFunctionAt(addr);
                String name = fn != null ? fn.getName() : ("FUN_" + hex);
                pw.println("======================================================");
                pw.println("FUNCTION " + name + " @ " + addr);
                pw.println("======================================================");
                if (fn != null) {
                    DecompileResults res = decomp.decompileFunction(
                            fn, 180, new ConsoleTaskMonitor());
                    if (res != null && res.decompileCompleted())
                        pw.println(res.getDecompiledFunction().getC());
                    else
                        pw.println("(decompile failed)");
                } else {
                    pw.println("(no function at this address)");
                }
                pw.println();
            }
        }
        println("Wrote callers + " + ADDRS.length + " functions to " + OUT_PATH);
    }
}
