// DecompileTCPDispatch.java — Ghidra headless script.
//
// Iter 10 follow-up: pefile byte search found that the immediate
// value 0x838f appears 3 times in code (at 0x0059dfa5, 0x0076fbec,
// 0x008a3a2e). 0x830c, 0x830d, 0xa002 don't appear as 32-bit
// immediates — they're handled byte-by-byte. So 0x838f is likely
// where the TCP dispatcher (or InteractionCommit emitter)
// special-cases the high-frequency InteractionCommit opcode.
//
// Decompile the containing functions to find the TCP opcode switch.
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient.exe -noanalysis \
//       -scriptPath /home/javier/Documents/Projects/Neocron/ceres-j/tools/ghidra \
//       -postScript DecompileTCPDispatch.java

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;

public class DecompileTCPDispatch extends GhidraScript {

    private static final String[] TARGETS = {
        "0059dfa5",
        "0076fbec",
        "008a3a2e",
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/tcp_dispatch_decomp.txt";

    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            for (String hex : TARGETS) {
                Address a = currentProgram.getAddressFactory()
                                           .getAddress(hex);
                Function f = listing.getFunctionContaining(a);
                pw.println("===== ref at 0x" + hex + " =====");
                if (f == null) {
                    pw.println("no containing function");
                    continue;
                }
                pw.println("function: " + f.getName()
                           + " @ " + f.getEntryPoint());
                DecompileResults res = decomp.decompileFunction(
                    f, 90, new ConsoleTaskMonitor());
                if (res != null && res.getDecompiledFunction() != null) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("// decompile failed");
                }
                pw.println();
            }
        }
        println("wrote " + OUT_PATH);
    }
}
