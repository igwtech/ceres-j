import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class DecompEquip2 extends GhidraScript {
    private static final String[] ADDRS = {
        "00649260",  // blob parser used by case 0x4c
        "00662da0",  // applier called after the two blobs parsed (case 0x4c)
        "0064b410",  // case 0x4b sibling
        "0065d3e0",  // case 0x18 applier
    };
    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/equip_decompile2.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        Listing listing = currentProgram.getListing();
        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            for (String hex : ADDRS) {
                Address addr = currentProgram.getAddressFactory().getAddress(hex);
                Function fn = listing.getFunctionAt(addr);
                String name = fn != null ? fn.getName() : ("FUN_" + hex);
                pw.println("==== FUNCTION " + name + " @ " + addr + " ====");
                if (fn != null) {
                    DecompileResults res = decomp.decompileFunction(fn, 180, new ConsoleTaskMonitor());
                    if (res != null && res.decompileCompleted())
                        pw.println(res.getDecompiledFunction().getC());
                    else pw.println("(decompile failed)");
                } else pw.println("(no function)");
                pw.println();
            }
        }
        println("Wrote " + OUT_PATH);
    }
}
