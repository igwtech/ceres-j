// REStateSync10.java (task #194) — pin the LC_RESTORECHAR vftable and
// the LC factory opcode. Scans for LC_RESTORECHAR symbols, dumps the
// vftable slots (slot3 = FUN_00841dc0 apply), and decompiles the
// reliable/LC dispatch FUN_0055ec10 + the ctors, then walks ctor refs
// to the LC message factory FUN_00840ee0 (case 0x11 == wire byte 0x12).
// Output: docs/re_state_sync_dump10.txt.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class REStateSync10 extends GhidraScript {

    private DecompInterface d;
    private Listing l;
    private PrintWriter pw;

    @Override
    protected void run() throws Exception {
        d = new DecompInterface();
        d.setOptions(new DecompileOptions());
        d.openProgram(currentProgram);
        l = currentProgram.getListing();
        String OUT = "/home/javier/Documents/Projects/Neocron/"
                + "ceres-j/docs/re_state_sync_dump10.txt";
        try (PrintWriter w = new PrintWriter(OUT)) {
            pw = w;
            SymbolTable st = currentProgram.getSymbolTable();
            List<Address> vtAddrs = new ArrayList<>();
            for (Symbol s : st.getAllSymbols(false)) {
                if (s.getName().contains("LC_RESTORECHAR")) {
                    pw.println("SYMBOL " + s.getName() + " @ "
                            + s.getAddress());
                    vtAddrs.add(s.getAddress());
                }
            }
            for (Address vt : vtAddrs) {
                Symbol ps = st.getPrimarySymbol(vt);
                if (ps == null || !ps.getName().contains("vftable"))
                    continue;
                pw.println("=== LC_RESTORECHAR::vftable @ " + vt + " ===");
                for (int i = 0; i < 16; i++) {
                    Address slot = vt.add((long) i * 4);
                    int v;
                    try { v = currentProgram.getMemory().getInt(slot); }
                    catch (Exception e) { break; }
                    Address tgt = vt.getNewAddress(v & 0xffffffffL);
                    Function tf = l.getFunctionAt(tgt);
                    pw.println(String.format("  slot[%2d] %s -> %08x %s",
                            i, slot, v, tf != null ? tf.getName() : ""));
                }
            }
            for (String h : new String[]{
                    "0055ec10", "0083fde0", "0083fe30"}) dec(h, 160);
            for (String h : new String[]{"0083fde0", "0083fe30"}) {
                Address a = currentProgram.getAddressFactory()
                        .getAddress(h);
                pw.println("#### REFS TO ctor " + h + " ####");
                for (Reference r : iter(currentProgram
                        .getReferenceManager().getReferencesTo(a))) {
                    Function cf = l.getFunctionContaining(
                            r.getFromAddress());
                    pw.println("  from=" + r.getFromAddress() + " "
                            + r.getReferenceType()
                            + (cf != null ? (" in " + cf.getName()
                                  + "@" + cf.getEntryPoint())
                              : " <data>"));
                    if (cf != null
                            && cf.getName().startsWith("thunk_")) {
                        for (Reference r2 : iter(currentProgram
                                .getReferenceManager()
                                .getReferencesTo(cf.getEntryPoint()))) {
                            Function cf2 = l.getFunctionContaining(
                                    r2.getFromAddress());
                            pw.println("     via-thunk from="
                                    + r2.getFromAddress()
                                    + (cf2 != null ? (" in "
                                          + cf2.getName() + "@"
                                          + cf2.getEntryPoint()) : ""));
                        }
                    }
                }
            }
        }
        println("done");
    }

    private List<Reference> iter(ReferenceIterator it) {
        List<Reference> r = new ArrayList<>();
        while (it.hasNext()) r.add(it.next());
        return r;
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
