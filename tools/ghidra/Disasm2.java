// Disasm2.java — raw disassembly of an address range (start,count) pairs from args.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import java.io.PrintWriter;

public class Disasm2 extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        String outPath = args[0];
        Listing listing = currentProgram.getListing();
        try (PrintWriter pw = new PrintWriter(outPath)) {
            for (int i = 1; i + 1 < args.length; i += 2) {
                Address a = currentProgram.getAddressFactory().getAddress(args[i]);
                int n = Integer.parseInt(args[i+1]);
                pw.println("=== disasm @ " + args[i] + " (" + n + " instrs) ===");
                Instruction instr = listing.getInstructionAt(a);
                for (int k = 0; k < n && instr != null; k++) {
                    pw.println("  " + instr.getAddress() + "  " + instr);
                    instr = instr.getNext();
                }
                pw.println();
            }
        }
        println("wrote " + outPath);
    }
}
