// FindScalarRefs.java — find all instructions that reference a specific
// constant scalar value, across the whole binary. Used to locate all
// call sites of specific UI event IDs and to answer "where does the
// client play the Synchronizing loading screen?".
//
// Hardcoded target: 0x3f3 (the "Synchronizing into City Zone" UI event
// played in FUN_0055aa30 case 0xc). We also check a few other candidate
// event IDs (0x3f2 "connecting please wait", 0x2712, 0x277b).

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.TreeMap;

public class FindScalarRefs extends GhidraScript {

    // Specific constants we care about. 0x3f3 is the Synchronizing UI
    // event; 0x3f2 is the "connecting please wait" event.
    private static final long[] TARGETS = {0x3f3L, 0x3f2L};

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/ui_event_refs.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();

        TreeMap<Long, TreeMap<Address, Function>> hits = new TreeMap<>();
        for (long t : TARGETS) {
            hits.put(t, new TreeMap<>());
        }

        FunctionIterator fit = fm.getFunctions(true);
        int fnCount = 0;
        while (fit.hasNext()) {
            Function fn = fit.next();
            fnCount++;
            InstructionIterator iit = listing.getInstructions(fn.getBody(), true);
            while (iit.hasNext()) {
                Instruction instr = iit.next();
                int numOps = instr.getNumOperands();
                for (int i = 0; i < numOps; i++) {
                    Object[] rep = instr.getOpObjects(i);
                    for (Object o : rep) {
                        if (o instanceof Scalar) {
                            long v = ((Scalar) o).getUnsignedValue();
                            if (hits.containsKey(v)) {
                                hits.get(v).putIfAbsent(fn.getEntryPoint(), fn);
                            }
                        }
                    }
                }
            }
            if (fnCount % 5000 == 0) {
                println("scanned " + fnCount + " functions");
            }
        }
        println("scanned " + fnCount + " functions total");

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Scalar operand references for UI event IDs");
            pw.println();
            for (var entry : hits.entrySet()) {
                long v = entry.getKey();
                var fns = entry.getValue();
                pw.println("======================================================");
                pw.println(String.format("TARGET 0x%x (%d functions)", v, fns.size()));
                pw.println("======================================================");
                for (Function fn : fns.values()) {
                    pw.println("------------------------------------------------------");
                    pw.println("FUNCTION " + fn.getName() + " @ " + fn.getEntryPoint());
                    pw.println("------------------------------------------------------");
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
        }
        println("Wrote " + OUT_PATH);
    }
}
