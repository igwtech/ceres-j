// Find code that writes to specific offsets of DAT_011633c0 (the global
// the HUD reads pool values from). The writer of these cells IS the
// canonical update path for the displayed pool numbers.

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.RefType;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;

import java.io.PrintWriter;

public class FindDatRefs extends GhidraScript {

    /** The base address used by the HUD pool renderer. */
    private static final long DAT_011633C0 = 0x011633c0L;

    /** Also check sibling globals 011633b4, 011633bc that appear in same function. */
    private static final long[] GLOBALS = {
        0x011633c0L, 0x011633b4L, 0x011633b8L, 0x011633bcL
    };

    private static final String OUT =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/hud_global_writers.txt";

    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();

        try (PrintWriter pw = new PrintWriter(OUT)) {
            for (long target : GLOBALS) {
                Address a = currentProgram.getAddressFactory().getAddress(
                        Long.toHexString(target));
                pw.println("=================================");
                pw.println("REFS TO " + a + ":");
                pw.println("=================================");
                ReferenceIterator it = rm.getReferencesTo(a);
                int n = 0;
                while (it.hasNext()) {
                    Reference r = it.next();
                    Address from = r.getFromAddress();
                    Function fn = listing.getFunctionContaining(from);
                    String fnName = fn != null ? fn.getName() : "(no fn)";
                    String fnEntry = fn != null ? fn.getEntryPoint().toString() : "n/a";
                    RefType t = r.getReferenceType();
                    Instruction in = listing.getInstructionAt(from);
                    String op = in != null ? in.toString() : "(no instr)";
                    String tag = "";
                    if (t.isWrite()) tag = "[WRITE]";
                    else if (t.isRead()) tag = "[READ]";
                    pw.println("  " + tag + " " + from + " in " + fnName
                            + " @ " + fnEntry + " :: " + op);
                    n++;
                }
                pw.println("  total: " + n);
                pw.println();
            }
        }
        println("wrote " + OUT);
    }
}
