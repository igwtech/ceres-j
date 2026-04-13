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

    // Functions to decompile — the UDP receive processing chain
    private static final String[] ADDRS = {
        "0055ec10",  // FUN_0055ec10 — called from recv handler to process data
        "0055e2f0",  // FUN_0055e2f0 — station lookup
        "0055f260",  // FUN_0055f260 — recvfrom wrapper
        "00560090",  // FUN_00560090 — sendto wrapper
        "005602f0",  // FUN_005602f0 — referenced by WINSOCKMGR
        "005609a0",  // FUN_005609a0 — referenced by WINSOCKMGR
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/udp_recv_chain.txt";

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
