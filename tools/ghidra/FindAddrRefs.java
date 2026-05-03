// FindAddrRefs.java — Use Ghidra's ReferenceManager to find every code
// reference TO a list of addresses (e.g. vtable bases). Returns the
// referencing instruction + its containing function.

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;

import java.io.PrintWriter;

public class FindAddrRefs extends GhidraScript {

    private static final String[] TARGETS = {
        // vtable B base + each slot — find every code path that calls
        // any handler in this packet-dispatch table
        "00a5d860", "00a5d864", "00a5d868", "00a5d86c", "00a5d870",
        "00a5d874", "00a5d878", "00a5d87c", "00a5d880", "00a5d884",
        "00a5d888", "00a5d88c", "00a5d890", "00a5d894", "00a5d898",
        "00a5d89c", "00a5d8a0",
    };

    private static final String OUT =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/charsys_addr_refs.txt";

    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();

        try (PrintWriter pw = new PrintWriter(OUT)) {
            for (String hex : TARGETS) {
                Address a = currentProgram.getAddressFactory().getAddress(hex);
                pw.println("=================================");
                pw.println("REF TO " + a);
                pw.println("=================================");
                ReferenceIterator it = rm.getReferencesTo(a);
                int n = 0;
                while (it.hasNext()) {
                    Reference r = it.next();
                    Address from = r.getFromAddress();
                    Function fn = listing.getFunctionContaining(from);
                    String fnName = fn != null ? fn.getName() : "(no fn)";
                    Address fnEntry = fn != null ? fn.getEntryPoint() : null;
                    pw.println("  " + from + " in " + fnName + " @ " + fnEntry
                            + "  type=" + r.getReferenceType());
                    n++;
                }
                pw.println("  total: " + n);
                pw.println();
            }
        }
        println("wrote " + OUT);
    }
}
