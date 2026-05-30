// FindPasswordEncoder.java — find auth packet builder + password encoder.
//
// Approach: locate functions referencing "password" string at 0x710624 and
// related auth-path strings. Decompile each. Also scan code for any function
// that contains the byte pair {0x84, 0x80} as immediate operands or in adjacent
// MOV instructions (TCP Auth packet header).
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient.exe -noanalysis \
//       -scriptPath /home/javier/Documents/Projects/Neocron/ceres-j/tools/ghidra \
//       -postScript FindPasswordEncoder.java

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.lang.OperandType;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;

public class FindPasswordEncoder extends GhidraScript {

    private static final String[] NEEDLES = new String[] {
        "password", "Password", "userid", "savelogin", "quickenter",
        "Incorrect user or password",
        "Connection failed, connected but auth timed out"
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/nc2-bot/docs/password_encoder_refs.txt";

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
                if (s.equals(needle) || s.contains(needle)) {
                    stringAddrs.computeIfAbsent(needle, k -> new LinkedHashSet<>())
                               .add(d.getAddress());
                }
            }
        }
        println("scanned " + stringsScanned + " data items, "
                + stringAddrs.size() + " needles matched");

        TreeMap<Address, Function> touched = new TreeMap<>();
        TreeMap<Address, StringBuilder> whyLog = new TreeMap<>();
        for (var entry : stringAddrs.entrySet()) {
            String needle = entry.getKey();
            for (Address addr : entry.getValue()) {
                println("needle '" + needle + "' @ " + addr);
                ReferenceIterator refs = refMgr.getReferencesTo(addr);
                while (refs.hasNext()) {
                    Reference r = refs.next();
                    Function fn = listing.getFunctionContaining(r.getFromAddress());
                    if (fn != null) {
                        Address ep = fn.getEntryPoint();
                        touched.putIfAbsent(ep, fn);
                        whyLog.computeIfAbsent(ep, k -> new StringBuilder())
                              .append("  ref ").append(r.getFromAddress())
                              .append(" -> '").append(needle).append("'\n");
                    }
                }
            }
        }
        println("=== " + touched.size() + " functions reference the needles ===");

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Functions referencing password/auth strings");
            for (Function fn : touched.values()) {
                pw.println("======================================================");
                pw.println("FUNCTION " + fn.getName() + " @ " + fn.getEntryPoint());
                pw.println("======================================================");
                StringBuilder why = whyLog.get(fn.getEntryPoint());
                if (why != null) {
                    pw.print(why);
                }
                pw.println();
                DecompileResults res = decomp.decompileFunction(
                        fn, 60, new ConsoleTaskMonitor());
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
