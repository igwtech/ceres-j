// REFraming5.java — dump the Client.cpp netbuffer class cluster
// (functions in 0x004b6e00 .. 0x004b9800) so we can read the
// 0x13 sub-splitter that calls the FUN_004b7190 enqueue with a
// per-sub _Size derived from the 2-byte sub-length.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;

public class REFraming5 extends GhidraScript {

    private static final long LO = 0x004b6e00L;
    private static final long HI = 0x004b9800L;

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_framing_dump5.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        Listing listing = currentProgram.getListing();

        int n = 0;
        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Client.cpp netbuffer class cluster "
                    + "[0x4b6e00 .. 0x4b9800]");
            pw.println();
            FunctionIterator it =
                    listing.getFunctions(true);
            while (it.hasNext()) {
                Function fn = it.next();
                long ep = fn.getEntryPoint().getOffset();
                if (ep < LO || ep >= HI || fn.isThunk()) {
                    continue;
                }
                DecompileResults res =
                        decomp.decompileFunction(fn, 90,
                                new ConsoleTaskMonitor());
                String c = (res != null
                        && res.decompileCompleted())
                        ? res.getDecompiledFunction().getC()
                        : "(decompile failed)";
                // Only keep functions that look like the sub
                // splitter: reference 0x13 / a 2-byte length loop
                // / call the enqueue, OR are small dispatch shims.
                pw.println("======================================================");
                pw.println("FUNCTION " + fn.getName() + " @ "
                        + fn.getEntryPoint());
                pw.println("======================================================");
                pw.println(c);
                pw.println();
                n++;
            }
        }
        println("Wrote " + n + " cluster functions to " + OUT_PATH);
    }
}
