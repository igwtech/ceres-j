// FindCharInfoDispatch.java — Ghidra headless script.
//
// Goal: identify the exact code path that calls FUN_0055c270 (the
// CharInfo / CharsysInfo dispatcher at offsets 0x2ae bits 0 and 1).
// We already know FUN_0055c270 checks byte[1] of its input payload.
// What we do NOT know is:
//   (a) which outer sub-packet type routes to FUN_0055c270, and
//   (b) whether the payload `param_1` it receives is the raw multipart
//       fragment payload or the *reassembled* multipart stream.
//
// Strategy:
//   - Find every caller of FUN_0055c270 and decompile each one.
//   - Find every caller of the multipart reassembly function (the one
//     that concats fragment payloads) and dump it too.
//   - Also dump FUN_0055c270 itself with more context.
//
// Writes to docs/charinfo_dispatch.txt.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.TreeMap;

public class FindCharInfoDispatch extends GhidraScript {

    private static final String TARGET_HEX = "0055c270";
    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/charinfo_dispatch.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        Address target = currentProgram.getAddressFactory()
                .getAddress(TARGET_HEX);
        Function targetFn = listing.getFunctionAt(target);
        if (targetFn == null) {
            println("ERROR: no function at " + TARGET_HEX);
            return;
        }

        TreeMap<Address, Function> callers = new TreeMap<>();
        ReferenceIterator refs = refMgr.getReferencesTo(target);
        while (refs.hasNext()) {
            Reference r = refs.next();
            Function fn = listing.getFunctionContaining(r.getFromAddress());
            if (fn != null) {
                callers.put(fn.getEntryPoint(), fn);
            }
        }

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Callers of FUN_" + TARGET_HEX + " and the target itself.");
            pw.println("# " + callers.size() + " callers found.");
            pw.println();

            pw.println("============================================");
            pw.println("TARGET FUNCTION FUN_" + targetFn.getName() + " @ " + targetFn.getEntryPoint());
            pw.println("============================================");
            DecompileResults tgtRes = decomp.decompileFunction(
                    targetFn, 240, new ConsoleTaskMonitor());
            if (tgtRes != null && tgtRes.decompileCompleted()) {
                pw.println(tgtRes.getDecompiledFunction().getC());
            }
            pw.println();

            for (Function fn : callers.values()) {
                pw.println("============================================");
                pw.println("CALLER " + fn.getName() + " @ " + fn.getEntryPoint());
                pw.println("============================================");
                DecompileResults res = decomp.decompileFunction(
                        fn, 240, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted()) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("(decompile failed)");
                }
                pw.println();
            }
        }
        println("Wrote target + " + callers.size() + " callers to " + OUT_PATH);
    }
}
