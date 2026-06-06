// FindLootRights.java — locate the "LootRights" emitter and dump the
// CHARSYS inventory item parser path. Helps determine which field in an
// item record routes it to the F2 grid vs. a loot/world object.
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient.exe -noanalysis \
//       -scriptPath /home/javier/Documents/Projects/Neocron/ceres-j/tools/ghidra \
//       -postScript FindLootRights.java

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

public class FindLootRights extends GhidraScript {

    private static final String[] NEEDLES = new String[] {
        "LootRights", "Loot Rights", "Current LootRights",
        "Yourself", "loot", "Loot",
    };

    // CHARSYS inventory functions named in CLAUDE.md / memory.
    private static final String[] FUNCS = new String[] {
        "0x008447d0", // CHARSYS TLV parser
        "0x00803cd0", // FULLCHARSYSTEM dispatcher
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/lootrights_refs.txt";

    private DecompInterface decomp;

    @Override
    protected void run() throws Exception {
        decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        TreeMap<String, Set<Address>> stringAddrs = new TreeMap<>();
        DataIterator dit = listing.getDefinedData(true);
        while (dit.hasNext()) {
            Data d = dit.next();
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
                    println(String.format("needle '%s' -> %s : \"%s\"",
                            needle, d.getAddress(), s));
                }
            }
        }

        TreeMap<Address, Function> touched = new TreeMap<>();
        TreeMap<Address, StringBuilder> whyLog = new TreeMap<>();
        for (var entry : stringAddrs.entrySet()) {
            String needle = entry.getKey();
            if (!needle.toLowerCase().contains("loot")) continue; // only loot strings for caller graph
            for (Address addr : entry.getValue()) {
                ReferenceIterator refs = refMgr.getReferencesTo(addr);
                while (refs.hasNext()) {
                    Reference r = refs.next();
                    Function fn = listing.getFunctionContaining(r.getFromAddress());
                    if (fn != null) {
                        Address ep = fn.getEntryPoint();
                        touched.putIfAbsent(ep, fn);
                        whyLog.computeIfAbsent(ep, k -> new StringBuilder())
                              .append("  ").append(r.getFromAddress())
                              .append(" refs '").append(needle)
                              .append("' @ ").append(addr).append("\n");
                    }
                }
            }
        }

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# LootRights emitters + CHARSYS inventory parser");
            pw.println();
            for (Function fn : touched.values()) {
                dumpFn(pw, fn, whyLog.get(fn.getEntryPoint()));
            }
            // Always dump the named CHARSYS functions
            for (String fa : FUNCS) {
                Address a = currentProgram.getAddressFactory()
                        .getAddress(fa.replace("0x",""));
                Function fn = listing.getFunctionAt(a);
                if (fn == null) fn = listing.getFunctionContaining(a);
                if (fn != null) dumpFn(pw, fn, null);
                else pw.println("(no function at " + fa + ")");
            }
        }
        println("Wrote " + OUT_PATH);
    }

    private void dumpFn(PrintWriter pw, Function fn, StringBuilder why) {
        pw.println("======================================================");
        pw.println("FUNCTION " + fn.getName() + " @ " + fn.getEntryPoint());
        pw.println("======================================================");
        if (why != null) { pw.println("Why:"); pw.print(why); pw.println(); }
        DecompileResults res = decomp.decompileFunction(fn, 180, new ConsoleTaskMonitor());
        if (res != null && res.decompileCompleted())
            pw.println(res.getDecompiledFunction().getC());
        else pw.println("(decompile failed)");
        pw.println();
    }
}
