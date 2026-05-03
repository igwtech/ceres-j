// FindScriptSpawnFail.java — Ghidra headless script.
//
// Locate the function that prints
//   "SCRIPTEDPLAYER : Script spawn failed '%s' NpcId: %d WorldId: %d"
// and dump its decompilation so we can see the exact failure path: what
// the lookup is keyed on, and which side of the comparison/branch causes
// the early-out that produces the log.
//
// Usage:
//   /opt/ghidra*/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient.exe -noanalysis \
//       -scriptPath /home/javier/Documents/Projects/Neocron/ceres-j/tools/ghidra \
//       -postScript FindScriptSpawnFail.java

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

public class FindScriptSpawnFail extends GhidraScript {

    private static final String[] NEEDLES = new String[] {
        // Specific HUD label format strings — these are the literals
        // the renderer prints next to the value. Finding them gives us
        // the function that DRAWS the HUD, and from there we trace
        // backward to the cell that holds the cash/soullight value.
        "CASH:",
        "CASH: ",
        " CASH",
        "SOULLIGHT:",
        "SOULLIGHT: ",
        "HLT:",
        "STA:",
        "PSI:",
        "%d / %d",        // pool format pattern
        "%i / %i",
        // Generic font-render functions that often print labels
        "DrawText",
        "RenderText",
        "PrintText",
        "DrawString",
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/hud_label_refs.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

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
                    println(String.format("needle '%s' -> %s : \"%s\"",
                            needle, d.getAddress(), s));
                }
            }
        }
        println("Scanned " + stringsScanned + " defined data items");

        Set<Function> calling = new java.util.LinkedHashSet<>();
        for (Set<Address> addrs : stringAddrs.values()) {
            for (Address a : addrs) {
                ReferenceIterator it = refMgr.getReferencesTo(a);
                while (it.hasNext()) {
                    Reference r = it.next();
                    Function f = listing.getFunctionContaining(r.getFromAddress());
                    if (f != null) {
                        calling.add(f);
                        println("string at " + a + " referenced by " + f.getName()
                                + " @ " + r.getFromAddress());
                    }
                }
            }
        }

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("=== FindScriptSpawnFail ===");
            pw.println("strings located:");
            for (var e : stringAddrs.entrySet()) {
                pw.println("  needle '" + e.getKey() + "' @ " + e.getValue());
            }
            pw.println();
            for (Function f : calling) {
                pw.println("====================================================");
                pw.println("function: " + f.getName() + " @ " + f.getEntryPoint());
                pw.println("====================================================");
                DecompileResults res = decomp.decompileFunction(
                        f, 60, new ConsoleTaskMonitor());
                if (res != null && res.getDecompiledFunction() != null) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("(decompile failed)");
                }
                pw.println();
            }
        }
        println("wrote: " + OUT_PATH);
    }
}
