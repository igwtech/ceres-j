// FindDrawPath.java — find the weapon draw / safe-zone gate by xref of the
// bone-name and zone strings the client uses on a draw. Robust string iteration
// (no DefinedDataIterator dependency).
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class FindDrawPath extends GhidraScript {
    private static final String[] NEEDLES = {
        "RHOLSTER","LHOLSTER","BHOLSTER","Safe Zone","Drop","Holster",
        "Weapon in App","Antizone","AntiZone","anti","Zone"
    };
    private static final String OUT =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/draw_path.txt";

    @Override
    protected void run() throws Exception {
        FunctionManager fm = currentProgram.getFunctionManager();
        Set<Function> refFns = new LinkedHashSet<>();
        try (PrintWriter pw = new PrintWriter(OUT)) {
            pw.println("##### string -> xref function map #####");
            DataIterator it = currentProgram.getListing().getDefinedData(true);
            while (it.hasNext()) {
                Data d = it.next();
                Object v = d.getValue();
                if (!(v instanceof String)) continue;
                String s = (String) v;
                boolean match = false;
                for (String n : NEEDLES) if (s.contains(n)) { match = true; break; }
                if (!match) continue;
                Reference[] refs = getReferencesTo(d.getAddress());
                if (refs.length == 0) continue;
                String disp = s.length() > 48 ? s.substring(0,48) : s;
                pw.printf("\"%s\" @ %s  (%d refs)%n", disp, d.getAddress(), refs.length);
                for (Reference r : refs) {
                    Function f = fm.getFunctionContaining(r.getFromAddress());
                    pw.printf("    %s  in %s%n", r.getFromAddress(),
                        f != null ? f.getName() : "?");
                    if (f != null) refFns.add(f);
                }
            }
            DecompInterface dc = new DecompInterface();
            dc.setOptions(new DecompileOptions());
            dc.openProgram(currentProgram);
            pw.println();
            pw.println("##### decompiled referencing functions #####");
            int n = 0;
            for (Function f : refFns) {
                if (n++ > 14) { pw.println("(.. more omitted ..)"); break; }
                pw.println("====== " + f.getName() + " @ " + f.getEntryPoint() + " ======");
                DecompileResults res = dc.decompileFunction(f, 90, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted())
                    pw.println(res.getDecompiledFunction().getC());
                else pw.println("(decompile failed)");
                pw.println();
            }
        }
        println("Wrote " + OUT + " (functions=" + refFns.size() + ")");
    }
}
