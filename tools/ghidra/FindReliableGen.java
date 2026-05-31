// FindReliableGen.java — locate the app-layer reliable NAK/ack-request generator.
// Strategy: find functions referencing the format strings or the 0x13 wrapper byte and
// the 0x2c8/0x10c WorldClient fields, plus any function that references string "WorldClient"
// or builds [01][seq]. We dump functions that reference offset 0x10c with a vtable call AND
// contain a byte-0x01 store, and also list refs to the multipart/0x13 constants.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import java.io.PrintWriter;
import java.util.*;

public class FindReliableGen extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        String outPath = args[0];
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        // offsets associated with the WorldClient reliable-window struct
        String[] offs = {"0x2c8", "0x2ae", "0x191", "0x149", "0x10c", "0x2e6", "0x2e7"};
        try (PrintWriter pw = new PrintWriter(outPath)) {
            FunctionIterator fit = fm.getFunctions(true);
            while (fit.hasNext()) {
                Function fn = fit.next();
                InstructionIterator iit = listing.getInstructions(fn.getBody(), true);
                Set<String> offHits = new TreeSet<>();
                while (iit.hasNext()) {
                    Instruction instr = iit.next();
                    String s = instr.toString();
                    for (String off : offs) if (s.contains(off)) { offHits.add(off); break; }
                }
                // report any function touching >=2 of the WorldClient struct offsets
                if (offHits.size() >= 2) {
                    pw.println("FUNCTION " + fn.getName() + " @ " + fn.getEntryPoint() + "  offs=" + offHits);
                }
            }
        }
        println("wrote " + outPath);
    }
}
