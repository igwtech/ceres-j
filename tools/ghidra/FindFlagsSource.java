// FindFlagsSource.java — trace the zone-flags word (bit 0x10 = Safe Zone).
// Callers of the flag-name builder FUN_006fee00 and the RHOLSTER draw fn
// FUN_007951d0, decompiled, so we can see which struct field / wire path
// feeds the flags and how the draw attaches the weapon model.
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.Reference;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class FindFlagsSource extends GhidraScript {
    private static final String[] TARGETS = { "006fee00", "007951d0" };
    private static final String OUT =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/flags_source.txt";

    @Override
    protected void run() throws Exception {
        FunctionManager fm = currentProgram.getFunctionManager();
        DecompInterface dc = new DecompInterface();
        dc.setOptions(new DecompileOptions());
        dc.openProgram(currentProgram);
        try (PrintWriter pw = new PrintWriter(OUT)) {
            Set<Function> toDump = new LinkedHashSet<>();
            for (String hex : TARGETS) {
                Address a = currentProgram.getAddressFactory().getAddress(hex);
                Function t = fm.getFunctionAt(a);
                pw.println("##### callers of FUN_" + hex + " ("
                    + (t != null ? t.getName() : "?") + ") #####");
                for (Reference r : getReferencesTo(a)) {
                    Function c = fm.getFunctionContaining(r.getFromAddress());
                    pw.printf("    %s  in %s%n", r.getFromAddress(),
                        c != null ? c.getName() : "?");
                    if (c != null) toDump.add(c);
                }
                if (t != null) toDump.add(t);
            }
            pw.println();
            pw.println("##### decompiled #####");
            int n = 0;
            for (Function f : toDump) {
                if (n++ > 10) { pw.println("(.. more omitted ..)"); break; }
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
