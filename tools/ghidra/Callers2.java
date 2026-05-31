// Callers2.java — list real callers (walking thunks) of addresses given as args.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import java.io.PrintWriter;
import java.util.*;

public class Callers2 extends GhidraScript {
    Listing listing;
    ReferenceManager rm;

    Set<Function> realCallers(Address target) {
        Set<Function> out = new LinkedHashSet<>();
        Deque<Address> stack = new ArrayDeque<>();
        stack.push(target);
        Set<Address> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            Address a = stack.pop();
            if (!seen.add(a)) continue;
            for (Reference r : rm.getReferencesTo(a)) {
                Address from = r.getFromAddress();
                Function f = listing.getFunctionContaining(from);
                if (f == null) continue;
                if (f.isThunk()) { stack.push(f.getEntryPoint()); continue; }
                out.add(f);
            }
        }
        return out;
    }

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        String outPath = args[0];
        listing = currentProgram.getListing();
        rm = currentProgram.getReferenceManager();
        try (PrintWriter pw = new PrintWriter(outPath)) {
            for (int i = 1; i < args.length; i++) {
                Address a = currentProgram.getAddressFactory().getAddress(args[i]);
                Function tf = listing.getFunctionContaining(a);
                pw.println("=== callers of " + args[i] + " (" + (tf!=null?tf.getName():"?") + ") ===");
                for (Function f : realCallers(a)) {
                    pw.println("  " + f.getEntryPoint() + "  " + f.getName());
                }
            }
        }
        println("Wrote " + outPath);
    }
}
