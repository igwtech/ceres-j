// FindSucceed.java — locate the function that logs "succeed!" so we can
// understand what code runs after the NetHost connect succeeds (or
// appears to succeed) in the client's worldclient startup flow.

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

public class FindSucceed extends GhidraScript {

    private static final String[] NEEDLES = new String[] {
        "succeed!",
        "Delete world for world change",
        "Create new world to change to",
        "World changes successfull",
        "WorldClient: Joining session",
        "HostName : %s",
        "Connecting to NetHost",
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/nethost_flow.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
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
                }
            }
        }
        for (var e : stringAddrs.entrySet()) {
            println("needle '" + e.getKey() + "' → " + e.getValue());
        }

        TreeMap<Address, Function> touched = new TreeMap<>();
        TreeMap<Address, StringBuilder> whyLog = new TreeMap<>();
        for (var entry : stringAddrs.entrySet()) {
            String needle = entry.getKey();
            for (Address addr : entry.getValue()) {
                ReferenceIterator refs = refMgr.getReferencesTo(addr);
                while (refs.hasNext()) {
                    Reference r = refs.next();
                    Address from = r.getFromAddress();
                    Function fn = listing.getFunctionContaining(from);
                    if (fn == null) {
                        // Walk backward to find nearest function start
                        long off = 0;
                        while (off < 0x2000 && fn == null) {
                            Address probe = from.subtract(off);
                            fn = listing.getFunctionAt(probe);
                            off++;
                        }
                    }
                    if (fn != null) {
                        Address ep = fn.getEntryPoint();
                        touched.putIfAbsent(ep, fn);
                        whyLog.computeIfAbsent(ep, k -> new StringBuilder())
                              .append("  ").append(from).append(" refs '")
                              .append(needle).append("' @ ").append(addr).append("\n");
                    }
                }
            }
        }

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Functions referencing NetHost / world-change flow strings");
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
