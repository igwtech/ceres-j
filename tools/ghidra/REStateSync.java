// REStateSync.java — Decompile the S->C state-sync + Type-15 actor-create chain.
// Targets: session dispatch, WWORLDMGR dispatcher + its caller, HUD ticks,
// FULLCHARSYSTEM dispatcher, CHARSYS TLV parser, actor-create allocators.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;

public class REStateSync extends GhidraScript {

    private static final String[] ADDRS = {
        "0055ec10",  // session dispatch (verified entry)
        "00541f20",  // WWORLDMGR message dispatcher (Type-15 / Corrupted log)
        "00803cd0",  // FULLCHARSYSTEM event dispatcher (vtable[8])
        "008447d0",  // CHARSYS TLV parser
        "007e87d0",  // HUD tick HP
        "007e8930",  // HUD tick PSI
        "007e8a20",  // HUD tick STA
        "00540ab0",  // WA spawn/route helper used by 0x1e/0x11/0x1d
        "005412d0",  // entity lookup by id
        "0069a580",  // SCRIPTEDPLAYER ctor variant A (param_3 struct)
        "00699fd0",  // SCRIPTEDPLAYER ctor variant B (raw byte stream)
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        Listing listing = currentProgram.getListing();

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            for (String hex : ADDRS) {
                Address addr = currentProgram.getAddressFactory().getAddress(hex);
                Function fn = listing.getFunctionAt(addr);
                String name = fn != null ? fn.getName() : ("FUN_" + hex);
                pw.println("======================================================");
                pw.println("FUNCTION " + name + " @ " + addr);
                if (fn != null) {
                    // list callers
                    Set<String> callers = new LinkedHashSet<>();
                    ReferenceIterator ri = currentProgram.getReferenceManager()
                            .getReferencesTo(addr);
                    while (ri.hasNext()) {
                        Reference r = ri.next();
                        Function cf = listing.getFunctionContaining(r.getFromAddress());
                        if (cf != null) callers.add(cf.getName() + " @ " + cf.getEntryPoint());
                    }
                    pw.println("CALLERS: " + callers);
                }
                pw.println("======================================================");
                if (fn != null) {
                    DecompileResults res = decomp.decompileFunction(
                            fn, 180, new ConsoleTaskMonitor());
                    if (res != null && res.decompileCompleted()) {
                        pw.println(res.getDecompiledFunction().getC());
                    } else {
                        pw.println("(decompile failed)");
                    }
                } else {
                    pw.println("(no function at this address)");
                }
                pw.println();
            }
        }
        println("Wrote " + ADDRS.length + " functions to " + OUT_PATH);
    }
}
