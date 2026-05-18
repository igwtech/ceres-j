// REStateSync8.java — Deep backward trace to the network opcode.
//
// REStateSync7 showed vtables reference the THUNK (thunk_FUN_00841dc0
// @ 00411c9d) not FUN_00841dc0 directly. So:
//  1) Find all refs TO the thunk 00411c9d (these are the vtable slots).
//  2) For each data ref, dump the pointer-table window to identify the
//     vtable base + slot index + the sibling slots (to ID the class).
//  3) Find refs to that vtable base (who instantiates / who calls
//     (*vt[slot])() ) — and decompile those callers.
//  4) Also: find which code reads in_ECX+0x14 / in_ECX+0x18 (the buffer
//     + len fields FUN_00841dc0 consumes) being WRITTEN — that's the
//     network handler that fills the CHARSYS single-packet buffer.
//  5) Decompile FUN_00558950 / FUN_00541800 / FUN_004b8fb0 (the app
//     dispatch loops) and FUN_004b8f00 (the 0x13 sub-splitter caller).

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
import java.util.ArrayList;
import java.util.List;

public class REStateSync8 extends GhidraScript {

    private DecompInterface decomp;
    private Listing listing;
    private PrintWriter pw;

    @Override
    protected void run() throws Exception {
        decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        listing = currentProgram.getListing();
        String OUT = "/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump8.txt";
        try (PrintWriter w = new PrintWriter(OUT)) {
            pw = w;

            // ---- thunks for the CHARSYS single-packet chain ----
            String[] thunks = { "00411c9d" /* thunk_FUN_00841dc0 */ };
            List<Address> vtableBases = new ArrayList<>();

            for (String th : thunks) {
                Address t = addr(th);
                pw.println("######################################################");
                pw.println("# REFS TO thunk " + th);
                pw.println("######################################################");
                ReferenceIterator it = currentProgram.getReferenceManager()
                        .getReferencesTo(t);
                while (it.hasNext()) {
                    Reference r = it.next();
                    Address from = r.getFromAddress();
                    MemoryBlock blk = currentProgram.getMemory().getBlock(from);
                    boolean isData = (blk != null && !blk.isExecute());
                    Function cf = listing.getFunctionContaining(from);
                    pw.println("  from=" + from + " type=" + r.getReferenceType()
                            + " block=" + (blk != null ? blk.getName() : "?")
                            + (cf != null ? (" inFunc=" + cf.getName()
                                  + "@" + cf.getEntryPoint()) : " inFunc=<data>"));
                    if (isData || cf == null) {
                        Address base = dumpPtrTable(from, t);
                        if (base != null) vtableBases.add(base);
                    }
                }
            }

            // ---- refs to each vtable base (who uses the class) ----
            for (Address vb : vtableBases) {
                pw.println();
                pw.println("######################################################");
                pw.println("# REFS TO vtable base " + vb);
                pw.println("######################################################");
                ReferenceIterator it = currentProgram.getReferenceManager()
                        .getReferencesTo(vb);
                int n = 0;
                while (it.hasNext() && n < 60) {
                    Reference r = it.next();
                    Address from = r.getFromAddress();
                    Function cf = listing.getFunctionContaining(from);
                    pw.println("  from=" + from + " type=" + r.getReferenceType()
                            + (cf != null ? (" inFunc=" + cf.getName()
                                  + "@" + cf.getEntryPoint()) : " inFunc=<data>"));
                    n++;
                }
            }

            // ---- app dispatch loops + sub-splitter caller ----
            for (String hex : new String[]{
                    "004b8f00", "00558950", "00541800",
                    "004b8fb0", "0055f5a0" }) {
                decompAddr(hex, 180);
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

    // Dump the surrounding pointer table; return the inferred vtable base
    // (first slot scanning back that still points into .text).
    private Address dumpPtrTable(Address ref, Address thunkTarget) {
        try {
            long base = ref.getOffset();
            pw.println("    --- ptr-table window around " + ref + " ---");
            Address inferredBase = null;
            int hereSlot = 0;
            for (int i = -40; i <= 40; i++) {
                Address slot = ref.getNewAddress(base + (long) i * 4L);
                int val;
                try { val = currentProgram.getMemory().getInt(slot); }
                catch (Exception e) { continue; }
                Address tgt = ref.getNewAddress(val & 0xffffffffL);
                Function tf = listing.getFunctionAt(tgt);
                MemoryBlock tb = currentProgram.getMemory().getBlock(tgt);
                boolean ptrToText = (tb != null && tb.isExecute());
                String mark = (i == 0) ? "  <== thunk_FUN_00841dc0 HERE" : "";
                pw.println(String.format(
                        "    slot[%+3d] %s -> %08x %s%s",
                        i, slot, val,
                        (tf != null ? tf.getName()
                            : (ptrToText ? "(code)" : "")), mark));
                if (i <= 0 && ptrToText && inferredBase == null
                        && i > -40) {
                    // tentative; refined below
                }
            }
            // Heuristic: vtable base = walk back while slots point to .text
            long b = base;
            while (true) {
                Address prev = ref.getNewAddress(b - 4);
                int v;
                try { v = currentProgram.getMemory().getInt(prev); }
                catch (Exception e) { break; }
                Address tgt = ref.getNewAddress(v & 0xffffffffL);
                MemoryBlock tb = currentProgram.getMemory().getBlock(tgt);
                if (tb == null || !tb.isExecute()) break;
                b -= 4;
            }
            inferredBase = ref.getNewAddress(b);
            hereSlot = (int) ((base - b) / 4);
            pw.println("    => inferred vtable base = " + inferredBase
                    + " ; thunk at slot index " + hereSlot);
            return inferredBase;
        } catch (Exception e) {
            pw.println("    (ptr-table dump failed: " + e + ")");
            return null;
        }
    }
}
