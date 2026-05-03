// Find code that WRITES to specific offsets of an object dereferenced
// from a global. We're looking for: store instructions where the
// destination is `[ECX+0x49]` etc., and the function also reads
// DAT_011633c0 as the base pointer. This locates the runtime writer
// of HUD pool values.

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;

import java.io.PrintWriter;
import java.util.HashSet;

public class FindOffsetWriters extends GhidraScript {

    private static final String[] OFFSETS = {"+0x49", "+0x4a", "+0x40", "+0x41"};

    private static final String OUT =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/pool_offset_writers.txt";

    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();

        try (PrintWriter pw = new PrintWriter(OUT)) {
            pw.println("# Functions writing to known HUD pool offsets");
            pw.println();

            FunctionIterator fit = fm.getFunctions(true);
            while (fit.hasNext()) {
                Function fn = fit.next();
                InstructionIterator iit = listing.getInstructions(fn.getBody(), true);
                HashSet<String> hits = new HashSet<>();
                while (iit.hasNext()) {
                    Instruction instr = iit.next();
                    String mnem = instr.getMnemonicString();
                    if (!"MOV".equals(mnem)) continue;
                    String op0 = instr.getDefaultOperandRepresentation(0);
                    if (op0 == null) continue;
                    // Look for writes: dest = [reg + smallOffset]
                    for (String off : OFFSETS) {
                        if (op0.contains(off + "]") || op0.contains(off + ",") ||
                                op0.endsWith(off)) {
                            String op1 = instr.getDefaultOperandRepresentation(1);
                            hits.add("    " + instr.getAddress() + " :: "
                                    + mnem + " " + op0 + ", " + op1);
                        }
                    }
                }
                if (!hits.isEmpty()) {
                    pw.println("FUNCTION " + fn.getName() + " @ " + fn.getEntryPoint());
                    for (String h : hits) pw.println(h);
                    pw.println();
                }
            }
        }
        println("wrote " + OUT);
    }
}
