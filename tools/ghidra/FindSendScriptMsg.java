// FindSendScriptMsg.java — Ghidra headless script.
//
// Find the C-side SendScriptMsg function in neocronclient.exe and dump
// its dispatch logic. SendScriptMsg is the bridge between the client's
// embedded Lua scripts and the wire protocol: scripts call
// SendScriptMsg("acceptmission", dialogclass) and the function maps
// the string command name to a numeric tag inside 0x03/0x1f.
//
// Strategy:
//   1. Find string literals matching the 55 known Lua RPC command names
//      (extracted from scripts.pak's dialogheader.lua).
//   2. For each string, find references — one of them will be the
//      dispatch table entry inside SendScriptMsg (or its callee).
//   3. Decompile the enclosing function and dump.
//
// Once we have the decompile, the string→numeric-tag mapping should be
// visible as a switch / strcmp ladder, giving us the complete tag
// catalog for `0x03/0x1f`.
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient.exe -noanalysis \
//       -scriptPath /home/javier/Documents/Projects/Neocron/ceres-j/tools/ghidra \
//       -postScript FindSendScriptMsg.java

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

public class FindSendScriptMsg extends GhidraScript {

    // The 55 known Lua RPC command names from dialogheader.lua.
    // Sorted by likelihood of being unique (long names first to avoid
    // matching unrelated strings like "say" inside other words).
    private static final String[] CMDS = new String[] {
        "ismissiontargetaccomplished",
        "epicrunaccomplished",
        "takespecialitemcnt",
        "activatedialogtrigger",
        "adddialogtriggercnt",
        "setnextdialogstate",
        "givespecialitem",
        "giveitemwithslots",
        "getfactionsympathy",
        "getbasesubskill",
        "getmissionstatus",
        "setmissionstatus",
        "getdoyalignment",
        "givequestitem",
        "takequestitem",
        "givetaggeditem",
        "showtuttext",
        "candoepicrun",
        "changefaction",
        "sendcustommsg",
        "sendlevelmsg",
        "getbaseskill",
        "getprofession",
        "getdistance",
        "getchildcnt",
        "getwoclevel",
        "getwocskill",
        "addwoclevel",
        "acceptmission",
        "startmission",
        "spawnnpcex",
        "setnpcscript",
        "takeitemcnt",
        "getsubskill",
        "givemoney",
        "takemoney",
        "giveitem",
        "takeitem",
        "spawnnpc",
        "npcaction",
        "sendemail",
        "settrigger",
        "gettrigger",
        "resettimer",
        "gettimer",
        "getclass",
        "getskill",
        "setanswer",
        "enddialog",
        "sayrsc",
        "trade",
        "rand",
        "say",
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/sendscriptmsg_dispatch.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        // First pass: find every defined string whose value EXACTLY matches
        // one of our command names.
        TreeMap<String, Set<Address>> cmdAddrs = new TreeMap<>();
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
            for (String cmd : CMDS) {
                if (s.equals(cmd)) {
                    cmdAddrs.computeIfAbsent(cmd, k -> new LinkedHashSet<>())
                            .add(d.getAddress());
                    println(String.format("cmd '%s' @ %s", cmd, d.getAddress()));
                    break; // string can only match one cmd (exact match)
                }
            }
        }
        println("scanned " + stringsScanned + " data items, "
                + cmdAddrs.size() + " command names located");

        // Find functions referencing these strings — count how many cmds
        // each function references. The function with the HIGHEST count
        // is almost certainly SendScriptMsg or its dispatcher.
        TreeMap<Address, Integer> cmdCountByFn = new TreeMap<>();
        TreeMap<Address, Function> fnByEntry = new TreeMap<>();
        TreeMap<Address, StringBuilder> whyLog = new TreeMap<>();

        for (var entry : cmdAddrs.entrySet()) {
            String cmd = entry.getKey();
            for (Address addr : entry.getValue()) {
                ReferenceIterator refs = refMgr.getReferencesTo(addr);
                while (refs.hasNext()) {
                    Reference r = refs.next();
                    Function fn = listing.getFunctionContaining(r.getFromAddress());
                    if (fn == null) continue;
                    Address ep = fn.getEntryPoint();
                    cmdCountByFn.merge(ep, 1, Integer::sum);
                    fnByEntry.putIfAbsent(ep, fn);
                    whyLog.computeIfAbsent(ep, k -> new StringBuilder())
                          .append(String.format("  '%s' @ %s -> ref @ %s%n",
                                                cmd, addr, r.getFromAddress()));
                }
            }
        }

        // Sort functions by descending command-count.
        var sortedFns = new java.util.ArrayList<>(cmdCountByFn.entrySet());
        sortedFns.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# SendScriptMsg dispatch — Ghidra dump");
            pw.println("# Functions ranked by number of distinct Lua RPC");
            pw.println("# command names they reference. The TOP function");
            pw.println("# is almost certainly SendScriptMsg or its dispatcher.");
            pw.println();

            int maxToDecompile = Math.min(5, sortedFns.size());
            for (int i = 0; i < sortedFns.size(); i++) {
                var e = sortedFns.get(i);
                Address ep = e.getKey();
                int count = e.getValue();
                Function fn = fnByEntry.get(ep);
                pw.printf("%-50s @ %s  references %d cmd strings%n",
                          fn.getName(), ep, count);
            }
            pw.println();

            for (int i = 0; i < maxToDecompile; i++) {
                var e = sortedFns.get(i);
                Address ep = e.getKey();
                Function fn = fnByEntry.get(ep);
                int count = e.getValue();
                pw.println("======================================================");
                pw.println("FUNCTION " + fn.getName() + " @ " + ep
                           + "  (refs " + count + " cmd names)");
                pw.println("======================================================");
                StringBuilder why = whyLog.get(ep);
                if (why != null) {
                    pw.println("Cmd refs:");
                    pw.print(why);
                    pw.println();
                }
                DecompileResults res = decomp.decompileFunction(
                        fn, 240, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted()) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("(decompile failed)");
                }
                pw.println();
            }
        }
        println("Wrote " + sortedFns.size() + " functions ranked + top "
                + Math.min(5, sortedFns.size()) + " decompiled to "
                + OUT_PATH);
    }
}
