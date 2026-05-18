// REStateSync5.java — who instantiates SCRIPTEDPLAYER (FUN_0069a580 /
// FUN_00699fd0)? Trace non-thunk callers + the WA-type registrar +
// the entity vtable slot 0x18 target reached from Type-0x28 case '('.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

public class REStateSync5 extends GhidraScript {

    private static final String[] TRACE = { "0069a580", "00699fd0" };

    private Listing listing;

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        listing = currentProgram.getListing();

        String OUT = "/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump5.txt";
        try (PrintWriter pw = new PrintWriter(OUT)) {
            Set<String> decompile = new LinkedHashSet<>();
            for (String hex : TRACE) {
                Address a = currentProgram.getAddressFactory().getAddress(hex);
                pw.println("##### NON-THUNK CALLERS OF " + hex + " #####");
                Set<String> seen = new LinkedHashSet<>();
                ArrayDeque<Address> work = new ArrayDeque<>();
                Set<String> visited = new LinkedHashSet<>();
                work.add(a);
                while (!work.isEmpty()) {
                    Address cur = work.poll();
                    if (!visited.add(cur.toString())) continue;
                    ReferenceIterator ri = currentProgram.getReferenceManager()
                            .getReferencesTo(cur);
                    while (ri.hasNext()) {
                        Reference r = ri.next();
                        Function cf = listing.getFunctionContaining(r.getFromAddress());
                        if (cf == null) continue;
                        if (cf.getName().startsWith("thunk_")) {
                            work.add(cf.getEntryPoint());
                        } else {
                            seen.add(cf.getName() + " @ " + cf.getEntryPoint()
                                + "  (callsite " + r.getFromAddress() + ")");
                            decompile.add(cf.getEntryPoint().toString());
                        }
                    }
                }
                for (String s : seen) pw.println("  " + s);
                pw.println();
            }

            for (String ep : decompile) {
                Address addr = currentProgram.getAddressFactory().getAddress(ep);
                Function fn = listing.getFunctionAt(addr);
                if (fn == null) continue;
                pw.println("======================================================");
                pw.println("FUNCTION " + fn.getName() + " @ " + addr);
                pw.println("======================================================");
                DecompileResults res = decomp.decompileFunction(
                        fn, 160, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted())
                    pw.println(res.getDecompiledFunction().getC());
                else
                    pw.println("(decompile failed)");
                pw.println();
            }
        }
        println("done -> " + OUT);
    }
}
