// FindStringRefs.java — Ghidra headless script.
//
// Find every function that references a set of key log strings related to
// the WorldClient sync state machine, and dump their decompilation. This
// is a complement to FindState2acWrites.java: the state-field write may be
// hidden behind an indirect pointer, but the log message next to it IS a
// scalar operand and will show up in the usual reference graph.
//
// Strings we care about are the ones printed on each sync-state transition:
//   "WC : Changepos set"            -> sets sync bit 3 (the one we never set)
//   "no worldchange entity found"   -> random startpos fallback
//   "WORLDCLIENT : Char sys info rcv %i"
//   "WorldClient: Synchronization with worldserver failed"
//   "WORLDCLIENT : World Change denied"
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient.exe -noanalysis \
//       -scriptPath /home/javier/Documents/Projects/Neocron/ceres-j/tools/ghidra \
//       -postScript FindStringRefs.java

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;

public class FindStringRefs extends GhidraScript {

    // Substrings of strings we want to locate. Matching is substring-based
    // because some have format specifiers or continue onto a second line.
    private static final String[] NEEDLES = new String[] {
        "WC : Changepos set",
        "no worldchange entity found",
        "Char sys info rcv",
        "Synchronization with worldserver",
        "World Change denied",
        "Client up to date",
        "sync to worldserver failed",
        "Connecting to worldserver failed",
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/state_string_refs.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        // First pass: find every defined string whose value contains a needle.
        // Keep a map needle -> list of addresses so we can report per string.
        TreeMap<String, Set<Address>> stringAddrs = new TreeMap<>();
        DataIterator dit = listing.getDefinedData(true);
        int stringsScanned = 0;
        while (dit.hasNext()) {
            Data d = dit.next();
            stringsScanned++;
            if (d.getDataType() == null) continue;
            String dtName = d.getDataType().getName().toLowerCase();
            if (!dtName.contains("string") && !dtName.contains("char")) continue;
            Object val = d.getValue();
            if (!(val instanceof String)) continue;
            String s = (String) val;
            for (String needle : NEEDLES) {
                if (s.contains(needle)) {
                    stringAddrs.computeIfAbsent(needle, k -> new LinkedHashSet<>())
                               .add(d.getAddress());
                    println(String.format("needle '%s' -> %s", needle, d.getAddress()));
                }
            }
        }
        println("scanned " + stringsScanned + " data items, "
                + stringAddrs.size() + " needles matched");

        // Second pass: for every matched address, get all incoming references
        // and find the enclosing function. Dedup by function entry.
        TreeMap<Address, Function> touched = new TreeMap<>();
        TreeMap<Address, StringBuilder> whyLog = new TreeMap<>();
        for (var entry : stringAddrs.entrySet()) {
            String needle = entry.getKey();
            for (Address addr : entry.getValue()) {
                ReferenceIterator refs = refMgr.getReferencesTo(addr);
                int n = 0;
                while (refs.hasNext()) {
                    Reference r = refs.next();
                    n++;
                    Function fn = listing.getFunctionContaining(r.getFromAddress());
                    if (fn != null) {
                        Address ep = fn.getEntryPoint();
                        touched.putIfAbsent(ep, fn);
                        whyLog.computeIfAbsent(ep, k -> new StringBuilder())
                              .append("  ").append(r.getFromAddress())
                              .append(" refs '").append(needle)
                              .append("' @ ").append(addr).append("\n");
                        println("  ref @ " + r.getFromAddress()
                                + " inside fn " + fn.getName());
                    } else {
                        // Reference from outside any function — usually means
                        // it's from data (vtable, jump table) or from code
                        // that Ghidra didn't recognize as part of a function.
                        // Walk backward from the reference site to the nearest
                        // enclosing function start and decompile that.
                        Address from = r.getFromAddress();
                        Function fallback = null;
                        long offset = 0;
                        while (offset < 0x2000 && fallback == null) {
                            Address probe = from.subtract(offset);
                            fallback = listing.getFunctionAt(probe);
                            offset++;
                        }
                        println("  ORPHAN ref @ " + from
                                + " (no enclosing fn, nearest start="
                                + (fallback != null ? fallback.getName() + " @ "
                                       + fallback.getEntryPoint() : "NONE")
                                + ")");
                        if (fallback != null) {
                            Address ep = fallback.getEntryPoint();
                            touched.putIfAbsent(ep, fallback);
                            whyLog.computeIfAbsent(ep, k -> new StringBuilder())
                                  .append("  ").append(from)
                                  .append(" refs '").append(needle)
                                  .append("' @ ").append(addr)
                                  .append(" (orphan, scanned back to fn start)\n");
                        }
                    }
                }
                println("needle '" + needle + "' @ " + addr
                        + " has " + n + " references");
            }
        }

        println("=== " + touched.size() + " functions reference the needles ===");

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Functions referencing WorldClient sync-state log strings");
            pw.println("# Generated by FindStringRefs.java");
            pw.println();
            for (Function fn : touched.values()) {
                pw.println("======================================================");
                pw.println("FUNCTION " + fn.getName() + " @ " + fn.getEntryPoint());
                pw.println("======================================================");
                StringBuilder why = whyLog.get(fn.getEntryPoint());
                if (why != null) {
                    pw.println("Why matched:");
                    pw.print(why);
                    pw.println();
                }
                DecompileResults res = decomp.decompileFunction(
                        fn, 120, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted()) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("(decompile failed)");
                }
                pw.println();
            }
        }
        println("Wrote " + touched.size() + " functions to " + OUT_PATH);
    }
}
