// FindLogSink.java — Ghidra headless script.
//
// Goal: locate the client's logging sink function(s). Strategy:
//   1. Scan defined strings for known log-line literals (the prefixes the
//      client writes to error_*.log / game log).
//   2. For each match, find the referencing instruction and its function.
//   3. At each reference site, find the CALL that consumes the string as an
//      argument and record the call target. The most-frequently-called
//      target across all log strings is the logging sink.
//   4. Decompile the top sink candidates and the immediately enclosing
//      functions for the highest-signal log strings.
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless . Neocron2clien \
//       -process -noanalysis \
//       -scriptPath ceres-j/tools/ghidra -postScript FindLogSink.java

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.RefType;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class FindLogSink extends GhidraScript {

    // Substrings of known log lines (from CLAUDE.md + error log known lines).
    private static final String[] NEEDLES = new String[] {
        "LSTPLAYER : Update Message corrupted",
        "Unable to Spawn WA",
        "Script spawn failed",
        "PWORLDHOST Connect to",
        "WINSOCKMGR",
        "Reading desc from file",
        "WorldClient: Joining session",
        "WorldServer: Connecting to WorldServer failed",
        "WORLDCLIENT",
        "WWORLDMGR",
        "SCRIPTEDPLAYER",
        "Synchronization with worldserver",
        "World Change denied",
        "Changepos set",
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/_logsink_raw.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        // string addr -> matched needle
        TreeMap<Address, String> strHits = new TreeMap<>();
        DataIterator dit = listing.getDefinedData(true);
        while (dit.hasNext()) {
            Data d = dit.next();
            if (d.getDataType() == null) continue;
            String dt = d.getDataType().getName().toLowerCase();
            if (!dt.contains("string") && !dt.contains("char")) continue;
            Object v = d.getValue();
            if (!(v instanceof String)) continue;
            String s = (String) v;
            for (String n : NEEDLES) {
                if (s.contains(n)) {
                    strHits.put(d.getAddress(), n);
                    println("STR @ " + d.getAddress() + "  needle='" + n
                            + "'  val=" + trunc(s));
                    break;
                }
            }
        }
        println("matched " + strHits.size() + " log strings");

        // For every reference to a matched string, walk forward in the
        // containing function from the ref site to the next CALL, and
        // tally the call target. The dominant target = the log sink.
        Map<Address, Integer> callTargetVotes = new HashMap<>();
        Map<Address, List<String>> callTargetWhy = new HashMap<>();
        Set<Address> refFunctions = new LinkedHashSet<>();

        for (Map.Entry<Address, String> e : strHits.entrySet()) {
            Address strAddr = e.getKey();
            String needle = e.getValue();
            ReferenceIterator refs = refMgr.getReferencesTo(strAddr);
            while (refs.hasNext()) {
                Reference r = refs.next();
                Address from = r.getFromAddress();
                Function fn = listing.getFunctionContaining(from);
                if (fn != null) refFunctions.add(fn.getEntryPoint());

                // Scan forward up to 40 instructions for a CALL.
                Instruction ins = listing.getInstructionAt(from);
                int hops = 0;
                while (ins != null && hops < 40) {
                    String mn = ins.getMnemonicString().toUpperCase();
                    if (mn.equals("CALL")) {
                        Address tgt = callTarget(ins);
                        if (tgt != null) {
                            callTargetVotes.merge(tgt, 1, Integer::sum);
                            callTargetWhy.computeIfAbsent(tgt, k -> new ArrayList<>())
                                .add(needle + " @str " + strAddr + " ref " + from);
                        }
                        break;
                    }
                    ins = ins.getNext();
                    hops++;
                }
            }
        }

        // Rank call targets.
        List<Map.Entry<Address, Integer>> ranked =
            new ArrayList<>(callTargetVotes.entrySet());
        ranked.sort((a, b) -> b.getValue() - a.getValue());

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Log sink discovery — raw");
            pw.println("# matched " + strHits.size() + " strings");
            pw.println();
            pw.println("== CALL TARGET RANKING (votes = times reached after a log str) ==");
            for (Map.Entry<Address, Integer> en : ranked) {
                Address t = en.getKey();
                Function tf = listing.getFunctionAt(t);
                String resolved = resolveThunk(t, listing);
                pw.println(String.format("votes=%-3d  target=%s  %s%s",
                        en.getValue(), t,
                        tf != null ? tf.getName() : "(no fn)",
                        resolved != null ? "  -> resolves to " + resolved : ""));
            }
            pw.println();

            // Decompile the top 5 sink candidates (resolved through thunk).
            pw.println("== TOP SINK CANDIDATE DECOMPILATIONS ==");
            int shown = 0;
            Set<Address> done = new LinkedHashSet<>();
            for (Map.Entry<Address, Integer> en : ranked) {
                if (shown >= 6) break;
                Address t = en.getKey();
                Function tf = listing.getFunctionAt(t);
                if (tf != null && tf.isThunk()) {
                    Function tt = tf.getThunkedFunction(true);
                    if (tt != null) { tf = tt; t = tt.getEntryPoint(); }
                }
                if (tf == null || !done.add(t)) continue;
                shown++;
                pw.println("------------------------------------------------------");
                pw.println("SINK CANDIDATE " + tf.getName() + " @ " + t
                        + "  (votes=" + en.getValue() + ")");
                List<String> why = callTargetWhy.get(en.getKey());
                if (why != null) for (String w : why) pw.println("  via " + w);
                pw.println("------------------------------------------------------");
                DecompileResults res = decomp.decompileFunction(
                        tf, 120, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted())
                    pw.println(res.getDecompiledFunction().getC());
                else
                    pw.println("(decompile failed)");
                pw.println();
            }

            // Decompile the functions that emit a few hallmark log lines so
            // we can see the gate around them.
            pw.println("== FUNCTIONS THAT EMIT KEY LOG LINES ==");
            for (Address fnEp : refFunctions) {
                Function fn = listing.getFunctionAt(fnEp);
                if (fn == null) continue;
                pw.println("------------------------------------------------------");
                pw.println("EMITTER " + fn.getName() + " @ " + fnEp);
                pw.println("------------------------------------------------------");
                DecompileResults res = decomp.decompileFunction(
                        fn, 120, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted())
                    pw.println(res.getDecompiledFunction().getC());
                else
                    pw.println("(decompile failed)");
                pw.println();
            }
        }
        println("Wrote " + OUT_PATH);
    }

    private String trunc(String s) {
        s = s.replace("\n", "\\n").replace("\r", "\\r");
        return s.length() > 70 ? s.substring(0, 70) + "..." : s;
    }

    private Address callTarget(Instruction ins) {
        Reference[] rs = ins.getReferencesFrom();
        for (Reference r : rs) {
            if (r.getReferenceType().isCall() || r.getReferenceType() == RefType.UNCONDITIONAL_CALL
                || r.getReferenceType().isFlow()) {
                return r.getToAddress();
            }
        }
        // Fallback: any addressable operand reference
        for (Reference r : rs) return r.getToAddress();
        return null;
    }

    private String resolveThunk(Address a, Listing listing) {
        Function f = listing.getFunctionAt(a);
        if (f != null && f.isThunk()) {
            Function tt = f.getThunkedFunction(true);
            if (tt != null) return tt.getName() + " @ " + tt.getEntryPoint();
        }
        return null;
    }
}
