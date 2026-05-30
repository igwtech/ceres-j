// FindSoullightHandler.java — Ghidra headless script.
//
// Task #139: identify the runtime Soullight S→C sub-opcode.
//
// Strategy: the client prints `"System: Soullight updated - Soullight %i"`
// when the player's soullight changes. The format string lives in
// .rdata at 0x00b31fd6. The function(s) that reference this string
// are the ones that process the SL update — and inside them we'll
// find the wire dispatcher case that fed them.
//
// Output:
//   1. All code references to the format string + their containing
//      function + the function's entry address (so we can grep our
//      catalog).
//   2. Full decompilation of the top-level caller (the dispatcher
//      case) so we can see which opcode triggered it.
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient.exe -noanalysis \
//       -scriptPath /home/javier/Documents/Projects/Neocron/ceres-j/tools/ghidra \
//       -postScript FindSoullightHandler.java

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

public class FindSoullightHandler extends GhidraScript {

    // Address from pefile dump 2026-05-29.
    private static final String TARGET = "00b31fd6";

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/soullight_handler.txt";

    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();
        Address target = currentProgram.getAddressFactory()
                                       .getAddress(TARGET);

        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Set<Function> callers = new LinkedHashSet<>();

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("=== refs to 0x" + TARGET + " ===");
            ReferenceIterator it = rm.getReferencesTo(target);
            while (it.hasNext()) {
                Reference r = it.next();
                Address from = r.getFromAddress();
                Function f = listing.getFunctionContaining(from);
                String fName = (f != null)
                    ? f.getName() + " @ " + f.getEntryPoint()
                    : "<no function>";
                pw.println("  from=" + from + "  fn=" + fName);
                if (f != null) callers.add(f);
            }
            pw.println();
            pw.println("=== decompilation of " + callers.size() +
                       " callers ===");
            for (Function f : callers) {
                pw.println();
                pw.println("//// " + f.getName() + " @ "
                           + f.getEntryPoint() + " ////");
                DecompileResults res = decomp.decompileFunction(
                    f, 30, new ConsoleTaskMonitor());
                if (res != null && res.getDecompiledFunction() != null) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("// decompile failed");
                }
            }
        }
        println("wrote " + OUT_PATH);
    }
}
