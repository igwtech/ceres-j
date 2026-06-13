// FindFlagWriter.java — find who WRITES the zone-flags word at
// *(DAT_011633b4 + 0x14). Scan refs to the global DAT_011633b4; in each
// referencing function look for a store to [reg+0x14]; decompile those.
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.OperandType;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Reference;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class FindFlagWriter extends GhidraScript {
    private static final String OUT =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/flag_writer.txt";

    @Override
    protected void run() throws Exception {
        FunctionManager fm = currentProgram.getFunctionManager();
        Listing listing = currentProgram.getListing();
        Address glob = currentProgram.getAddressFactory().getAddress("011633b4");

        Set<Function> refFns = new LinkedHashSet<>();
        try (PrintWriter pw = new PrintWriter(OUT)) {
            pw.println("##### functions referencing DAT_011633b4 #####");
            for (Reference r : getReferencesTo(glob)) {
                Function f = fm.getFunctionContaining(r.getFromAddress());
                String t = r.getReferenceType().toString();
                pw.printf("  %s  %-10s in %s%n", r.getFromAddress(), t,
                    f != null ? f.getName() : "?");
                if (f != null) refFns.add(f);
            }

            // For each referencing function, scan for a store to [reg + 0x14]
            pw.println();
            pw.println("##### candidates writing [reg + 0x14] (the flags field) #####");
            Set<Function> writers = new LinkedHashSet<>();
            for (Function f : refFns) {
                InstructionIterator it = listing.getInstructions(f.getBody(), true);
                while (it.hasNext()) {
                    Instruction ins = it.next();
                    String m = ins.getMnemonicString();
                    boolean store = m.startsWith("MOV") && ins.getNumOperands() >= 2
                        && (ins.getOperandType(0) & OperandType.DYNAMIC) != 0;
                    if (!store) continue;
                    boolean has14 = false;
                    for (Object o : ins.getOpObjects(0))
                        if (o instanceof Scalar && ((Scalar)o).getUnsignedValue()==0x14) has14=true;
                    if (has14) {
                        pw.printf("  %s  %s   in %s%n", ins.getAddress(), ins.toString(),
                            f.getName());
                        writers.add(f);
                    }
                }
            }

            DecompInterface dc = new DecompInterface();
            dc.setOptions(new DecompileOptions());
            dc.openProgram(currentProgram);
            pw.println();
            pw.println("##### decompiled writer candidates #####");
            int n = 0;
            for (Function f : writers) {
                if (n++ > 6) { pw.println("(.. more omitted ..)"); break; }
                pw.println("====== " + f.getName() + " @ " + f.getEntryPoint() + " ======");
                DecompileResults res = dc.decompileFunction(f, 90, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted())
                    pw.println(res.getDecompiledFunction().getC());
                else pw.println("(decompile failed)");
                pw.println();
            }
        }
        println("Wrote " + OUT);
    }
}
