// REStateSync3.java — the netmessage router that feeds FUN_00541f20,
// and the SCRIPTEDPLAYER WA-type vtable handler (Type 15 parser).

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class REStateSync3 extends GhidraScript {

    private static final String[] ADDRS = {
        "00558950",  // netmessage router -> FUN_00541f20 (opcode->Type)
        "00541800",  // alt caller of FUN_00541f20
        "004b8fb0",  // alt caller of FUN_00541f20
        "0069a580",  // SCRIPTEDPLAYER ctor A (the Type-15 struct consumer)
        "00699fd0",  // SCRIPTEDPLAYER ctor B (the raw-byte-stream Type-15/30)
        "004d8a50",  // WA spawn factory (registry walk)
        "007e7c00",  // time-delta fn used by HUD ticks (confirm no net input)
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump3.txt";

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
                    DecompileResults res = decomp.decompileFunction(
                            fn, 220, new ConsoleTaskMonitor());
                    if (res != null && res.decompileCompleted())
                        pw.println(res.getDecompiledFunction().getC());
                    else
                        pw.println("(decompile failed)");
                } else {
                    pw.println("(no function at this address)");
                }
                pw.println();
            }
        }
        println("Wrote " + ADDRS.length + " functions to " + OUT_PATH);
    }
}
