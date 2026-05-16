// FindLogGate.java — Ghidra headless script.
//
// FUN_004471c0 is the log sink. Its verbosity gate is the global
// DAT_00b05078 (an int log-level threshold). This script:
//   1. Lists every reference to DAT_00b05078 (where the level is read/written)
//      and decompiles the writing functions (init / config / cmdline parse).
//   2. Lists references to companion globals DAT_00b05100 (file-open flag),
//      DAT_00b050b0 (the ofstream), DAT_00b0507c (mutex).
//   3. Scans defined strings for the log-file name and any config/registry/
//      verbosity keywords ("@L", "loglevel", "log_level", "debug",
//      "verbose", ".cfg", ".ini", "console", "developer", a registry path),
//      and decompiles their referencing functions.
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless . Neocron2clien \
//       -process -noanalysis \
//       -scriptPath ceres-j/tools/ghidra -postScript FindLogGate.java

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

public class FindLogGate extends GhidraScript {

    private static final String[] GLOBALS = {
        "00b05078", // log-level threshold
        "00b05100", // file-open / init flag
        "00b050b0", // ofstream object
        "00b0507c", // log mutex
        "00b05078",
    };

    private static final String[] STR_NEEDLES = {
        "loglevel", "log_level", "LogLevel", "LOGLEVEL",
        "verbose", "Verbose", "VERBOSE",
        "debug", "Debug", "DEBUG",
        "console", "Console", "developer", "Developer",
        ".log", "error_", "logfile", "LogFile", "log file",
        "neocron.ini", ".ini", ".cfg", ".cconfig", "config",
        "Software\\", "SOFTWARE\\", "HKEY", "Reflexive", "Neocron",
        "@L", "-log", "-debug", "/debug", "-console",
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/_loggate_raw.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Log gate discovery — raw");
            pw.println();

            Set<Address> emitters = new LinkedHashSet<>();

            for (String hex : GLOBALS) {
                Address g = currentProgram.getAddressFactory().getAddress(hex);
                pw.println("======================================================");
                pw.println("GLOBAL DAT_" + hex + " @ " + g);
                pw.println("======================================================");
                ReferenceIterator refs = refMgr.getReferencesTo(g);
                int n = 0;
                while (refs.hasNext()) {
                    Reference r = refs.next();
                    n++;
                    Address from = r.getFromAddress();
                    Function fn = listing.getFunctionContaining(from);
                    pw.println("  " + r.getReferenceType() + " from " + from
                            + (fn != null ? "  in " + fn.getName()
                                    + " @ " + fn.getEntryPoint() : "  (orphan)"));
                    if (fn != null) emitters.add(fn.getEntryPoint());
                }
                pw.println("  total refs = " + n);
                pw.println();
            }

            // String scan.
            pw.println("======================================================");
            pw.println("STRING KEYWORD SCAN");
            pw.println("======================================================");
            TreeMap<Address, String> hits = new TreeMap<>();
            DataIterator dit = listing.getDefinedData(true);
            while (dit.hasNext()) {
                Data d = dit.next();
                if (d.getDataType() == null) continue;
                String dt = d.getDataType().getName().toLowerCase();
                if (!dt.contains("string") && !dt.contains("char")) continue;
                Object v = d.getValue();
                if (!(v instanceof String)) continue;
                String s = (String) v;
                if (s.length() > 200) continue;
                for (String nd : STR_NEEDLES) {
                    if (s.contains(nd)) {
                        hits.put(d.getAddress(), s);
                        break;
                    }
                }
            }
            for (var e : hits.entrySet()) {
                Address sa = e.getKey();
                String s = e.getValue().replace("\n", "\\n").replace("\r", "\\r");
                ReferenceIterator refs = refMgr.getReferencesTo(sa);
                StringBuilder rb = new StringBuilder();
                int rc = 0;
                while (refs.hasNext() && rc < 12) {
                    Reference r = refs.next();
                    Function fn = listing.getFunctionContaining(r.getFromAddress());
                    rb.append("    ref ").append(r.getFromAddress())
                      .append(fn != null ? " in " + fn.getName()
                              + " @ " + fn.getEntryPoint() : " (orphan)")
                      .append("\n");
                    if (fn != null) emitters.add(fn.getEntryPoint());
                    rc++;
                }
                pw.println("STR @ " + sa + "  \"" + s + "\""
                        + (rc == 0 ? "  (NO REFS)" : ""));
                pw.print(rb);
            }
            pw.println();

            // Decompile every emitter function once.
            pw.println("======================================================");
            pw.println("EMITTER / GATE FUNCTION DECOMPILATIONS");
            pw.println("======================================================");
            for (Address ep : emitters) {
                Function fn = listing.getFunctionAt(ep);
                if (fn == null) continue;
                pw.println("------------------------------------------------------");
                pw.println("FN " + fn.getName() + " @ " + ep
                        + (fn.isThunk() ? " [THUNK]" : ""));
                pw.println("------------------------------------------------------");
                DecompileResults res = decomp.decompileFunction(
                        fn, 90, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted())
                    pw.println(res.getDecompiledFunction().getC());
                else
                    pw.println("(decompile failed)");
                pw.println();
            }
        }
        println("Wrote " + OUT_PATH);
    }
}
