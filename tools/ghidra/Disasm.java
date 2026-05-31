// Disasm.java — raw disassembly of an address range. args: outpath, startHex, count
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import java.io.PrintWriter;

public class Disasm extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        String outPath = args[0];
        Listing listing = currentProgram.getListing();
        try (PrintWriter pw = new PrintWriter(outPath)) {
            for (int i = 1; i + 1 < args.length; i += 2) {
                Address a = currentProgram.getAddressFactory().getAddress(args[i]);
                int n = Integer.parseInt(args[i+1]);
                pw.println("=== from " + args[i] + " (" + n + " instrs) ===");
                Instruction ins = listing.getInstructionAt(a);
                for (int k = 0; k < n && ins != null; k++) {
                    pw.println(ins.getAddress() + ":  " + ins.toString());
                    ins = ins.getNext();
                }
            }
        }
        println("Wrote " + outPath);
    }
}
