// FindFlagWord.java — trace the zone-flags word (0x10=Safe Zone) back to its
// receive path. FUN_006fee00 (flag-name builder) is reached via a thunk; find
// the REAL callers of that thunk and decompile them to see which struct field
// feeds the flags, then we can find the packet handler that writes it.
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.Reference;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class FindFlagWord extends GhidraScript {
    private static final String OUT =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/flag_word.txt";

    @Override
    protected void run() throws Exception {
        FunctionManager fm = currentProgram.getFunctionManager();
        DecompInterface dc = new DecompInterface();
        dc.setOptions(new DecompileOptions());
        dc.openProgram(currentProgram);

        Set<Function> realCallers = new LinkedHashSet<>();
        try (PrintWriter pw = new PrintWriter(OUT)) {
            Address target = currentProgram.getAddressFactory().getAddress("006fee00");
            // 1) refs to FUN_006fee00 -> thunk functions
            Set<Function> thunks = new LinkedHashSet<>();
            for (Reference r : getReferencesTo(target)) {
                Function f = fm.getFunctionContaining(r.getFromAddress());
                if (f != null) thunks.add(f);
            }
            pw.println("##### thunks/direct callers of FUN_006fee00 #####");
            for (Function t : thunks) pw.println("  " + t.getName() + " @ " + t.getEntryPoint());

            // 2) refs to each thunk -> real callers
            pw.println();
            pw.println("##### real callers (refs to the thunk entry) #####");
            for (Function t : thunks) {
                for (Reference r : getReferencesTo(t.getEntryPoint())) {
                    Function c = fm.getFunctionContaining(r.getFromAddress());
                    pw.printf("  %s  in %s%n", r.getFromAddress(),
                        c != null ? c.getName() : "?");
                    if (c != null) realCallers.add(c);
                }
            }

            // 3) decompile real callers
            pw.println();
            pw.println("##### decompiled real callers #####");
            int n = 0;
            for (Function f : realCallers) {
                if (n++ > 6) { pw.println("(.. more omitted ..)"); break; }
                pw.println("====== " + f.getName() + " @ " + f.getEntryPoint() + " ======");
                DecompileResults res = dc.decompileFunction(f, 120, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted())
                    pw.println(res.getDecompiledFunction().getC());
                else pw.println("(decompile failed)");
                pw.println();
            }
        }
        println("Wrote " + OUT + " (realCallers=" + realCallers.size() + ")");
    }
}
