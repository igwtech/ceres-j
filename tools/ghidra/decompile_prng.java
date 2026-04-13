import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class decompile_prng extends GhidraScript {
    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        String[] addrs = {"004e36e0", "0055ff30"};
        String out = "/home/javier/Documents/Projects/Neocron/ceres-j/docs/prng_decompile.txt";
        try (PrintWriter pw = new PrintWriter(out)) {
            for (String hex : addrs) {
                Address addr = currentProgram.getAddressFactory().getAddress(hex);
                Function fn = currentProgram.getListing().getFunctionAt(addr);
                pw.println("=== " + (fn != null ? fn.getName() : hex) + " @ " + hex + " ===");
                if (fn != null) {
                    DecompileResults res = decomp.decompileFunction(fn, 120, new ConsoleTaskMonitor());
                    if (res != null && res.decompileCompleted())
                        pw.println(res.getDecompiledFunction().getC());
                    else pw.println("(failed)");
                } else pw.println("(no function)");
                pw.println();
            }
        }
        println("Done → " + out);
    }
}
