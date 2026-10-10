// FindCoWriters.java — Ghidra headless script.
//
// Find functions that STORE to every one of a set of struct offsets (e.g.
// the fields a packet handler fills), then decompile them. Matching is on
// the operand text of store instructions ("[RAX + 0x350]" etc.), so it is
// base-register agnostic.
//
// Usage (args: <out-file> <offset> [<offset> ...], offsets in hex):
//   analyzeHeadless ... -postScript FindCoWriters.java /tmp/out.txt 0x338 0x350

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.*;

public class FindCoWriters extends GhidraScript {

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        String out = args[0];
        List<String> offs = new ArrayList<>();
        for (int i = 1; i < args.length; i++) offs.add(" + " + args[i].toLowerCase() + "]");

        DecompInterface dec = new DecompInterface();
        dec.setOptions(new DecompileOptions());
        dec.openProgram(currentProgram);

        try (PrintWriter pw = new PrintWriter(out)) {
            for (Function fn : currentProgram.getFunctionManager().getFunctions(true)) {
                Map<String, String> found = new LinkedHashMap<>();
                InstructionIterator it = currentProgram.getListing().getInstructions(fn.getBody(), true);
                while (it.hasNext()) {
                    Instruction ins = it.next();
                    if (!ins.getMnemonicString().startsWith("MOV")) continue;
                    if (ins.getNumOperands() < 2) continue;
                    String dst = ins.getDefaultOperandRepresentation(0).toLowerCase();
                    for (String o : offs) {
                        if (dst.endsWith(o) && !found.containsKey(o)) {
                            found.put(o, ins.getAddress() + "  " + ins);
                        }
                    }
                }
                if (found.size() != offs.size()) continue;
                pw.println("======================================================");
                pw.println("FUNCTION " + fn.getName() + " @ " + fn.getEntryPoint());
                for (String v : found.values()) pw.println("  " + v);
                pw.println("======================================================");
                DecompileResults r = dec.decompileFunction(fn, 180, new ConsoleTaskMonitor());
                pw.println(r != null && r.decompileCompleted()
                        ? r.getDecompiledFunction().getC() : "(decompile failed)");
            }
        }
        println("wrote " + out);
    }
}
