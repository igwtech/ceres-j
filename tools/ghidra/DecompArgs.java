// DecompArgs.java — decompile a list of function addresses passed as args.
// args: outPath, addr1, addr2, ...
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class DecompArgs extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        String outPath = args[0];
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        Listing listing = currentProgram.getListing();
        try (PrintWriter pw = new PrintWriter(outPath)) {
            for (int i = 1; i < args.length; i++) {
                String hex = args[i];
                Address addr = currentProgram.getAddressFactory().getAddress(hex);
                Function fn = listing.getFunctionAt(addr);
                if (fn == null) {
                    fn = currentProgram.getFunctionManager().getFunctionContaining(addr);
                }
                String name = fn != null ? fn.getName() : ("FUN_" + hex);
                pw.println("======================================================");
                pw.println("FUNCTION " + name + " @ " + (fn!=null?fn.getEntryPoint():addr));
                pw.println("======================================================");
                if (fn != null) {
                    DecompileResults res = decomp.decompileFunction(fn, 120, new ConsoleTaskMonitor());
                    if (res != null && res.decompileCompleted()) {
                        pw.println(res.getDecompiledFunction().getC());
                    } else {
                        pw.println("(decompile failed)");
                    }
                } else {
                    pw.println("(no function at this address)");
                }
                pw.println();
            }
        }
        println("Wrote to " + outPath);
    }
}
