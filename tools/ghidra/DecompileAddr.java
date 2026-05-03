// DecompileAddr.java — Decompile specific function addresses.
// Quick-use script: edit ADDRS array and run.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;

public class DecompileAddr extends GhidraScript {

    // Functions to decompile — the SCRIPTEDPLAYER spawn lookup chain
    private static final String[] ADDRS = {
        "005b66c0",  // ONLY writer of DAT_011633c0 — sets up the HUD pool source pointer
        "00726c50",  // HUD pool render function (uses "%d / %d")
        "0070e100",  // alt pool render with byte values
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/hud_pool_setup.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# UDP receive processing chain decompilations");
            pw.println();
            for (String hex : ADDRS) {
                Address addr = currentProgram.getAddressFactory()
                        .getAddress(hex);
                Function fn = listing.getFunctionAt(addr);
                String name = fn != null ? fn.getName() : ("FUN_" + hex);
                pw.println("======================================================");
                pw.println("FUNCTION " + name + " @ " + addr);
                pw.println("======================================================");
                if (fn != null) {
                    DecompileResults res = decomp.decompileFunction(
                            fn, 120, new ConsoleTaskMonitor());
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
