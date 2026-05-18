// REStateSync9.java (task #194) — decompile the CHARSYS LC dispatch
// table users (FUN_0083fde0/FUN_0083fe30/FUN_008405d0 = LC_RESTORECHAR
// ctors/dtor), the live-parse chain (FUN_008033d0/FUN_00841dc0), and the
// sibling vtable slot handlers (FUN_008408e0/008437b0/00842680/00840940
// — serialize/deserialize). Output: docs/re_state_sync_dump9.txt.
//
// Decisive find: FUN_008437b0 writes `*buf = 0x12` (the LC_RESTORECHAR
// wire type byte); FUN_00842680 reads [len LE2] + memcpy into obj+0x18;
// FUN_00841dc0 (slot 3) applies via FUN_008033d0 -> FUN_008447d0 +
// FUN_0080b8b0 (same pipeline as UI event 0x6e).

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

public class REStateSync9 extends GhidraScript {

    private DecompInterface d;
    private Listing l;
    private PrintWriter pw;

    private static final String[] DECOMP = {
        "0083fde0", "0083fe30", "008405d0",   // LC_RESTORECHAR ctors/dtor
        "008033d0", "00841dc0",               // live parse chain
        "008408e0", "008437b0", "00842680", "00840940", // vtable siblings
    };

    @Override
    protected void run() throws Exception {
        d = new DecompInterface();
        d.setOptions(new DecompileOptions());
        d.openProgram(currentProgram);
        l = currentProgram.getListing();
        String OUT = "/home/javier/Documents/Projects/Neocron/"
                + "ceres-j/docs/re_state_sync_dump9.txt";
        try (PrintWriter w = new PrintWriter(OUT)) {
            pw = w;
            for (String h : DECOMP) dec(h, 200);
            for (String h : new String[]{"0083fde0", "0083fe30",
                    "008405d0"}) {
                Address a = currentProgram.getAddressFactory()
                        .getAddress(h);
                pw.println("#### REFS TO " + h + " ####");
                ReferenceIterator it = currentProgram
                        .getReferenceManager().getReferencesTo(a);
                while (it.hasNext()) {
                    Reference r = it.next();
                    Address f = r.getFromAddress();
                    Function cf = l.getFunctionContaining(f);
                    pw.println("  from=" + f + " " + r.getReferenceType()
                            + (cf != null ? (" in " + cf.getName()
                                  + "@" + cf.getEntryPoint())
                              : " <data>"));
                }
            }
        }
        println("done");
    }

    private void dec(String h, int s) {
        Address a = currentProgram.getAddressFactory().getAddress(h);
        Function fn = l.getFunctionAt(a);
        if (fn == null) fn = l.getFunctionContaining(a);
        pw.println("======================================================");
        pw.println("FUNCTION " + (fn != null ? fn.getName()
                : ("FUN_" + h)) + " @ "
                + (fn != null ? fn.getEntryPoint() : a));
        pw.println("======================================================");
        if (fn != null) {
            DecompileResults r = d.decompileFunction(fn, s,
                    new ConsoleTaskMonitor());
            if (r != null && r.decompileCompleted())
                pw.println(r.getDecompiledFunction().getC());
            else pw.println("(decompile failed)");
        } else pw.println("(no fn)");
        pw.println();
    }
}
