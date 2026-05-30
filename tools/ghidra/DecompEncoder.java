// Decompile FUN_005f4490 (suspected password encoder)

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;

public class DecompEncoder extends GhidraScript {

    private static final String[] ADDRS = {
        "005f4490",  // thunk target — should be resolved to real fn
        "0075eb10",  // called for byte at offset 6 — likely encryption_key source
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/nc2-bot/docs/encoder_5f4490.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            for (String hex : ADDRS) {
                Address addr = currentProgram.getAddressFactory().getAddress(hex);
                Function fn = listing.getFunctionAt(addr);
                pw.println("===================================");
                pw.println("FUNCTION @ " + addr + " name=" + (fn != null ? fn.getName() : "?"));
                pw.println("===================================");
                if (fn == null) {
                    // Try function containing
                    fn = listing.getFunctionContaining(addr);
                    pw.println("(no fn at addr; containing fn=" + (fn!=null?fn.getName()+" @ "+fn.getEntryPoint():"none") + ")");
                    if (fn == null) continue;
                }
                pw.println("Callers:");
                ReferenceIterator ri = refMgr.getReferencesTo(fn.getEntryPoint());
                while (ri.hasNext()) {
                    Reference r = ri.next();
                    Function caller = listing.getFunctionContaining(r.getFromAddress());
                    pw.println("  from " + r.getFromAddress()
                            + (caller != null ? " in " + caller.getName() + " @ " + caller.getEntryPoint() : ""));
                }
                pw.println();
                DecompileResults res = decomp.decompileFunction(fn, 120, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted()) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("(decompile failed)");
                }
                pw.println();
            }
        }
        println("done");
    }
}
