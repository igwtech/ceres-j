// DecompEquip.java — decompile equip/holster dispatcher + apply path.
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class DecompEquip extends GhidraScript {
    private static final String[] ADDRS = {
        "0064ec90",  // PlayerAction dispatcher (0x03/0x1f sub-action switch; case 0x4c equip)
        "007fcaf0",  // per-stat delta apply N*7B [op][f32][u16 target]
        "007f94f0",  // op table for FUN_007fcaf0
        "00803cd0",  // FULLCHARSYSTEM dispatcher (case 0x18/0x1a)
    };
    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/equip_decompile.txt";

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
                pw.println("======================================================");
                pw.println("FUNCTION " + name + " @ " + addr);
                pw.println("======================================================");
                if (fn != null) {
                    DecompileResults res = decomp.decompileFunction(fn, 180, new ConsoleTaskMonitor());
                    if (res != null && res.decompileCompleted())
                        pw.println(res.getDecompiledFunction().getC());
                    else pw.println("(decompile failed)");
                } else pw.println("(no function at this address)");
                pw.println();
            }
        }
        println("Wrote to " + OUT_PATH);
    }
}
