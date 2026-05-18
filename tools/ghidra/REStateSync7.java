// REStateSync7.java — Backward trace: which S->C network opcode routes to
// vtable-B slot8 -> FUN_00841dc0 -> FUN_008033d0 (the single-packet CHARSYS
// handler that fires FULLCHARSYSTEM UI event 0x6e -> FUN_008447d0/0080b8b0).
//
// Strategy:
//  1) Decompile FUN_00841dc0 and FUN_008033d0 (callee that fires 0x6e).
//  2) Find all references TO FUN_00841dc0 (direct calls + pointer refs in
//     data, i.e. vtable slots).
//  3) For each data reference (vtable), dump the surrounding pointer table
//     so we can identify slot index 8 and the class.
//  4) Find the reliable/LC dispatch sites (FUN_0055ec10 family) and the
//     application dispatch (FUN_00541f20 / FUN_004b8cd0 loop) and dump them
//     so the opcode->slot route is visible.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class REStateSync7 extends GhidraScript {

    // functions to fully decompile for the forward picture
    private static final String[] DECOMP = {
        "00841dc0", // vtable-B slot8 thunk -> FUN_008033d0
        "008033d0", // fires UI event 0x6e (must verify)
        "00803cd0", // FULLCHARSYSTEM dispatcher (case 0x6e)
        "0055c270", // 0x03/0x07 multipart reassembler (disc 0x01/0x02)
        "0055ec10", // reliable/LC dispatch
    };

    private DecompInterface decomp;
    private Listing listing;
    private PrintWriter pw;

    @Override
    protected void run() throws Exception {
        decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        listing = currentProgram.getListing();
        String OUT = "/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump7.txt";
        try (PrintWriter w = new PrintWriter(OUT)) {
            pw = w;

            // ---- 1. forward decompiles ----
            for (String hex : DECOMP) decompAddr(hex, 200);

            // ---- 2. references TO FUN_00841dc0 ----
            Address t = addr("00841dc0");
            pw.println("######################################################");
            pw.println("# REFERENCES TO FUN_00841dc0 (vtable-B slot8 thunk)");
            pw.println("######################################################");
            ReferenceIterator it = currentProgram.getReferenceManager()
                    .getReferencesTo(t);
            while (it.hasNext()) {
                Reference r = it.next();
                Address from = r.getFromAddress();
                MemoryBlock blk = currentProgram.getMemory().getBlock(from);
                String bname = blk != null ? blk.getName() : "?";
                Function cf = listing.getFunctionContaining(from);
                pw.println("  from=" + from + " type=" + r.getReferenceType()
                        + " block=" + bname
                        + (cf != null ? (" inFunc=" + cf.getName()
                              + "@" + cf.getEntryPoint()) : " inFunc=<data>"));
                // If it's a data ref (vtable slot), dump the pointer table
                // window around it: 16 pointers before .. 16 after.
                if (cf == null || (blk != null && !blk.isExecute())) {
                    dumpPtrTable(from);
                }
            }

            // ---- 3. references TO FUN_008033d0 (the callee firing 0x6e) ----
            Address t2 = addr("008033d0");
            pw.println();
            pw.println("######################################################");
            pw.println("# REFERENCES TO FUN_008033d0");
            pw.println("######################################################");
            it = currentProgram.getReferenceManager().getReferencesTo(t2);
            while (it.hasNext()) {
                Reference r = it.next();
                Address from = r.getFromAddress();
                Function cf = listing.getFunctionContaining(from);
                pw.println("  from=" + from + " type=" + r.getReferenceType()
                        + (cf != null ? (" inFunc=" + cf.getName()
                              + "@" + cf.getEntryPoint()) : " inFunc=<data>"));
            }
        }
        println("done -> " + OUT);
    }

    private Address addr(String hex) {
        return currentProgram.getAddressFactory().getAddress(hex);
    }

    private void decompAddr(String hex, int secs) {
        Address a = addr(hex);
        Function fn = listing.getFunctionAt(a);
        if (fn == null) fn = listing.getFunctionContaining(a);
        pw.println("======================================================");
        pw.println("FUNCTION " + (fn != null ? fn.getName() : ("FUN_" + hex))
                + " @ " + (fn != null ? fn.getEntryPoint() : a));
        pw.println("======================================================");
        if (fn != null) {
            DecompileResults res = decomp.decompileFunction(
                    fn, secs, new ConsoleTaskMonitor());
            if (res != null && res.decompileCompleted())
                pw.println(res.getDecompiledFunction().getC());
            else pw.println("(decompile failed)");
        } else pw.println("(no function at " + hex + ")");
        pw.println();
    }

    // Dump a pointer table window: walk back to find table start by reading
    // 4-byte LE pointers; print 24 slots before and after with slot index.
    private void dumpPtrTable(Address ref) {
        try {
            long base = ref.getOffset();
            pw.println("    --- pointer-table window around " + ref + " ---");
            for (int i = -24; i <= 24; i++) {
                Address slot = ref.getNewAddress(base + (long) i * 4L);
                int val;
                try {
                    val = currentProgram.getMemory().getInt(slot);
                } catch (Exception e) {
                    continue;
                }
                Address tgt = ref.getNewAddress(val & 0xffffffffL);
                Function tf = listing.getFunctionAt(tgt);
                String mark = (i == 0) ? "  <== FUN_00841dc0 HERE" : "";
                pw.println(String.format(
                        "    slot[%+3d] %s -> %08x %s%s",
                        i, slot, val,
                        (tf != null ? tf.getName() : ""), mark));
            }
        } catch (Exception e) {
            pw.println("    (ptr-table dump failed: " + e + ")");
        }
    }
}
