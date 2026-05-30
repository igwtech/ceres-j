// DecompileSoullightDispatch.java — Ghidra headless script.
//
// Task #139 follow-up: from pefile xref scan we know address
// 0x0083079e references the "SOULLIGHT" string. Likely an opcode
// dispatcher case-arm. Decompile the function containing that
// address so we can read off the case value.
//
// Also decompile 0x007b9d54 (Good/Neutral/Bad classifier) — useful
// for confirming the HUD update side.
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient.exe -noanalysis \
//       -scriptPath /home/javier/Documents/Projects/Neocron/ceres-j/tools/ghidra \
//       -postScript DecompileSoullightDispatch.java

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;

public class DecompileSoullightDispatch extends GhidraScript {

    private static final String[] TARGETS = {
        "0083079e",
        "007b9d54",
        "007b9fa5",
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/soullight_decomp.txt";

    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            for (String hex : TARGETS) {
                Address a = currentProgram.getAddressFactory()
                                           .getAddress(hex);
                Function f = listing.getFunctionContaining(a);
                pw.println("===== ref at 0x" + hex + " =====");
                if (f == null) {
                    pw.println("no containing function");
                    continue;
                }
                pw.println("function: " + f.getName() +
                           " @ " + f.getEntryPoint());
                DecompileResults res = decomp.decompileFunction(
                    f, 60, new ConsoleTaskMonitor());
                if (res != null && res.getDecompiledFunction() != null) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("// decompile failed");
                }
                pw.println();
            }
        }
        println("wrote " + OUT_PATH);
    }
}
