// FindState2acWrites.java — Ghidra headless script.
//
// Goal: locate the missing state 2 → 3 transition in the NC2 client's
// WorldClient state machine. The state field lives at offset +0x2ac on
// the WorldClient object. Known direct writes:
//
//   FUN_0055a5e0                  : +0x2ac = 1  (accepted)
//   FUN_0055bdc0 state 1          : +0x2ac = 2  (JoinSession called)
//   FUN_0055bdc0 state 2 timeout  : 15s → login-screen bounce
//   FUN_00559920 case 3           : +0x2ac = 4  (time-sync received)
//   FUN_00559920 case 5           : +0x2ac = 3  (World Change denied — abnormal)
//
// There is no literal "= 3" write on the normal path, so the transition
// must happen via an indirect pointer, a computed expression, or a
// callback invoked from the NetMgr session layer (FUN_005f24c0 /
// FUN_005f3570). This script searches the entire binary for ANY
// instruction that references an offset of 0x2ac and prints the
// decompiled enclosing function so we can eyeball whichever handler
// touches the state field.
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient.exe -noanalysis \
//       -scriptPath /home/javier/Documents/Projects/Neocron/ceres-j/tools/ghidra \
//       -postScript FindState2acWrites.java

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

public class FindState2acWrites extends GhidraScript {

    private static final long STATE_OFFSET = 0x2acL;
    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/state_2ac_callsites.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        // Ordered by entry point for deterministic output.
        TreeMap<Address, Function> touched = new TreeMap<>();
        // Per-function list of hit addresses so we can annotate the dump.
        TreeMap<Address, StringBuilder> hitLog = new TreeMap<>();
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        println("FunctionManager says: " + fm.getFunctionCount() + " functions");
        println("Listing.getNumInstructions: " + listing.getNumInstructions());

        int scanned = 0;
        int fnCount = 0;

        // Iterate over every function body and examine each instruction.
        // Direct Listing.getInstructions(true) returned 0 in headless mode —
        // iterating via the FunctionManager sidesteps that.
        FunctionIterator fit = fm.getFunctions(true);
        while (fit.hasNext()) {
            Function fn = fit.next();
            fnCount++;
            InstructionIterator iit = listing.getInstructions(fn.getBody(), true);
            while (iit.hasNext()) {
                Instruction instr = iit.next();
                scanned++;
                int numOps = instr.getNumOperands();
                boolean hit = false;
                for (int i = 0; i < numOps && !hit; i++) {
                    Object[] rep = instr.getOpObjects(i);
                    for (Object o : rep) {
                        if (o instanceof Scalar) {
                            long v = ((Scalar) o).getUnsignedValue();
                            if (v == STATE_OFFSET) {
                                hit = true;
                                break;
                            }
                        }
                    }
                }
                if (hit) {
                    Address ep = fn.getEntryPoint();
                    touched.putIfAbsent(ep, fn);
                    hitLog.computeIfAbsent(ep, k -> new StringBuilder())
                          .append("  ").append(instr.getAddress())
                          .append(" : ").append(instr).append("\n");
                }
            }
            if (fnCount % 2000 == 0) {
                println("scanned " + fnCount + " functions ("
                        + scanned + " instructions), touched=" + touched.size());
            }
        }

        println("=== Scan complete. " + fnCount + " functions / " + scanned
                + " instructions examined, " + touched.size()
                + " functions touch +0x2ac ===");

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Functions that reference offset 0x2ac");
            pw.println("# Generated by FindState2acWrites.java");
            pw.println("# " + touched.size() + " functions found");
            pw.println();
            for (Function fn : touched.values()) {
                pw.println("======================================================");
                pw.println("FUNCTION " + fn.getName() + " @ " + fn.getEntryPoint());
                pw.println("======================================================");
                StringBuilder hits = hitLog.get(fn.getEntryPoint());
                if (hits != null) {
                    pw.println("Hit instructions:");
                    pw.print(hits);
                    pw.println();
                }
                DecompileResults res = decomp.decompileFunction(
                        fn, 120, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted()) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("(decompile failed)");
                }
                pw.println();
            }
        }
        println("Wrote " + touched.size() + " functions to " + OUT_PATH);
    }
}
