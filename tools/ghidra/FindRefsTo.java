// FindRefsTo.java — list ALL references (any type) to addresses given as args, with context.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import java.io.PrintWriter;

public class FindRefsTo extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        String outPath = args[0];
        Listing listing = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();
        try (PrintWriter pw = new PrintWriter(outPath)) {
            for (int i = 1; i < args.length; i++) {
                Address a = currentProgram.getAddressFactory().getAddress(args[i]);
                pw.println("=== refs to " + args[i] + " ===");
                for (Reference r : rm.getReferencesTo(a)) {
                    Address from = r.getFromAddress();
                    Function f = listing.getFunctionContaining(from);
                    Data d = listing.getDataContaining(from);
                    String ctx = (f != null ? ("FN " + f.getName()) : (d != null ? "DATA@" + d.getAddress() : "?"));
                    pw.println("  " + from + "  " + r.getReferenceType() + "  " + ctx);
                }
            }
        }
        println("Wrote " + outPath);
    }
}
