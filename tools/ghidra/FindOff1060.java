// FindOff1060.java — find functions that reference struct offsets 0x1060/0x1064/0x1068
// (the app/WorldClient handler pointer + alive-rep state) and report which WRITE them.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import java.io.PrintWriter;
import java.util.*;

public class FindOff1060 extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        String outPath = args[0];
        String[] offs = {"0x1060", "0x1064", "0x1068", "0x106c", "0x1070"};
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        try (PrintWriter pw = new PrintWriter(outPath)) {
            FunctionIterator fit = fm.getFunctions(true);
            while (fit.hasNext()) {
                Function fn = fit.next();
                InstructionIterator iit = listing.getInstructions(fn.getBody(), true);
                List<String> hits = new ArrayList<>();
                boolean writes = false;
                while (iit.hasNext()) {
                    Instruction instr = iit.next();
                    String s = instr.toString();
                    for (String off : offs) {
                        if (s.contains(off)) {
                            String mnem = instr.getMnemonicString();
                            String op0 = instr.getDefaultOperandRepresentation(0);
                            boolean w = "MOV".equals(mnem) && op0 != null && op0.contains("[") && op0.contains(off);
                            if (w) writes = true;
                            hits.add("    " + instr.getAddress() + " :: " + s + (w ? "   <== WRITE" : ""));
                            break;
                        }
                    }
                }
                if (!hits.isEmpty()) {
                    pw.println("FUNCTION " + fn.getName() + " @ " + fn.getEntryPoint() + (writes ? "   [HAS WRITES]" : ""));
                    for (String h : hits) pw.println(h);
                    pw.println();
                }
            }
        }
        println("wrote " + outPath);
    }
}
