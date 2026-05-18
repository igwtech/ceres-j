// REFraming.java — pin the application-layer framing chain.
//
// Decompiles the functions that map a wire 0x13 sub-packet
// (subLen + [0x03][seq LE2][op][data]) to the client's
// ClientNetBuffer queue [size LE4][channel 1B][body] read by
// FUN_004b8cd0. Target: prove what `param_5`/`desc[0]` (the
// message size) is and where the [0x03][seq] strip happens.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class REFraming extends GhidraScript {

    // Seed functions whose callers + bodies we need.
    private static final String[] SEEDS = {
        "0055ec10", // reliable/LC dispatch (default = payload, -0x0F)
        "0055c270", // 0x03/0x07 multipart reassembler
        "004b8cd0", // ClientNetBuffer reader -> {size,body,channel}
        "0055ff30", // recvfrom + decrypt loop
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_framing_dump.txt";

    private DecompInterface decomp;

    @Override
    protected void run() throws Exception {
        decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        Listing listing = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();

        Set<Address> toDump = new LinkedHashSet<>();
        for (String hex : SEEDS) {
            Address a = addr(hex);
            toDump.add(a);
            // real callers (walk thunks)
            for (Function caller : realCallers(a)) {
                toDump.add(caller.getEntryPoint());
            }
        }

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Application-layer framing chain decompilations");
            pw.println("# seeds: " + String.join(", ", SEEDS));
            pw.println();
            for (Address a : toDump) {
                Function fn = listing.getFunctionAt(a);
                String name = fn != null ? fn.getName() : ("FUN_" + a);
                pw.println("======================================================");
                pw.println("FUNCTION " + name + " @ " + a);
                List<Function> rc = fn != null ? realCallers(a)
                        : new ArrayList<>();
                StringBuilder cs = new StringBuilder();
                for (Function c : rc) {
                    cs.append(c.getName()).append('@')
                      .append(c.getEntryPoint()).append(' ');
                }
                pw.println("REAL CALLERS: " + cs);
                pw.println("======================================================");
                if (fn != null) {
                    DecompileResults res = decomp.decompileFunction(
                            fn, 120, new ConsoleTaskMonitor());
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
        println("Wrote " + toDump.size() + " functions to " + OUT_PATH);
    }

    private Address addr(String hex) {
        return currentProgram.getAddressFactory().getAddress(hex);
    }

    private List<Function> realCallers(Address target) {
        List<Function> out = new ArrayList<>();
        Set<Address> seen = new LinkedHashSet<>();
        collect(target, out, seen, 0);
        return out;
    }

    private void collect(Address target, List<Function> out,
            Set<Address> seen, int depth) {
        if (depth > 4 || !seen.add(target)) {
            return;
        }
        ReferenceManager rm = currentProgram.getReferenceManager();
        for (Reference ref : rm.getReferencesTo(target)) {
            Address from = ref.getFromAddress();
            Function f = currentProgram.getFunctionManager()
                    .getFunctionContaining(from);
            if (f == null) {
                continue;
            }
            if (f.isThunk()) {
                collect(f.getEntryPoint(), out, seen, depth + 1);
            } else if (!out.contains(f)) {
                out.add(f);
            }
        }
    }
}
