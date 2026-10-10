// DecompAt.java — decompile the functions containing the given addresses.
// Args: <out-file> <hex-addr> [<hex-addr> ...]
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class DecompAt extends GhidraScript {
    @Override protected void run() throws Exception {
        String[] a = getScriptArgs();
        DecompInterface d = new DecompInterface();
        d.setOptions(new DecompileOptions());
        d.openProgram(currentProgram);
        try (PrintWriter pw = new PrintWriter(a[0])) {
            for (int i = 1; i < a.length; i++) {
                Function fn = getFunctionContaining(toAddr(a[i]));
                pw.println("======== " + a[i] + " -> " + (fn == null ? "no function" : fn.getName() + " @ " + fn.getEntryPoint()));
                if (fn == null) continue;
                DecompileResults r = d.decompileFunction(fn, 180, new ConsoleTaskMonitor());
                pw.println(r != null && r.decompileCompleted() ? r.getDecompiledFunction().getC() : "(decompile failed)");
            }
        }
    }
}
