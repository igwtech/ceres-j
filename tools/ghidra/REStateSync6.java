// REStateSync6.java — the SCRIPTEDPLAYER allocator (FUN_006b1640) and the
// route that supplies WA-type/size/stream into FUN_00567e50 (the 0x1000f
// path). Also the spawn-request opcode helper FUN_00540ab0 callee chain.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class REStateSync6 extends GhidraScript {

    private static final String[] ADDRS = {
        "006b1640",  // SCRIPTEDPLAYER allocator (-> FUN_00699fd0)
        "004d8a70",  // registry add (pairs with FUN_004d8a50 walk) -- guess
        "0053daa8",  // FUN_0053d040 callsite context (calls FUN_004d8a50)
        "0053d040",  // a FUN_004d8a50 caller (likely the WA-spawn dispatcher entry)
        "00540ab0",  // WA spawn helper (already have, re-emit with more lines)
    };

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        Listing listing = currentProgram.getListing();
        String OUT = "/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump6.txt";
        try (PrintWriter pw = new PrintWriter(OUT)) {
            for (String hex : ADDRS) {
                Address addr = currentProgram.getAddressFactory().getAddress(hex);
                Function fn = listing.getFunctionAt(addr);
                if (fn == null) fn = listing.getFunctionContaining(addr);
                pw.println("======================================================");
                pw.println("FUNCTION " + (fn != null ? fn.getName() : ("FUN_"+hex))
                           + " @ " + (fn != null ? fn.getEntryPoint() : addr));
                pw.println("======================================================");
                if (fn != null) {
                    DecompileResults res = decomp.decompileFunction(
                            fn, 160, new ConsoleTaskMonitor());
                    if (res != null && res.decompileCompleted())
                        pw.println(res.getDecompiledFunction().getC());
                    else pw.println("(decompile failed)");
                } else pw.println("(no function)");
                pw.println();
            }
        }
        println("done -> " + OUT);
    }
}
