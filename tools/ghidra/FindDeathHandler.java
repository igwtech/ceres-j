// FindDeathHandler.java — Find all functions referencing death/respawn strings
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class FindDeathHandler extends GhidraScript {
    private static final String[] NEEDLES = {
        "dead", "Dead", "DEAD", "death", "Death",
        "die", "Die", "kill", "Kill", "killed",
        "respawn", "Respawn", "RESPAWN",
        "genrep", "GenRep", "GENREP",
        "you died", "You Died",
        "health", "Health",
        "DeathRespawn", "death_respawn",
    };

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        TreeMap<Address, Function> touched = new TreeMap<>();
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
                if (s.toLowerCase().contains(needle.toLowerCase())) {
                    ReferenceIterator refs = refMgr.getReferencesTo(d.getAddress());
                    while (refs.hasNext()) {
                        Reference r = refs.next();
                        Function fn = listing.getFunctionContaining(r.getFromAddress());
                        if (fn != null) {
                            touched.putIfAbsent(fn.getEntryPoint(), fn);
                            println("needle '" + needle + "' in '" + s.substring(0, Math.min(60, s.length()))
                                + "' → " + fn.getName());
                        }
                    }
                    break;
                }
            }
        }

        String outPath = "/home/javier/Documents/Projects/Neocron/ceres-j/docs/death_handler.txt";
        try (PrintWriter pw = new PrintWriter(outPath)) {
            pw.println("# Death/Respawn handler functions");
            pw.println("# " + touched.size() + " functions found\n");
            for (Function fn : touched.values()) {
                pw.println("==============================");
                pw.println("FUNCTION " + fn.getName() + " @ " + fn.getEntryPoint());
                pw.println("==============================");
                DecompileResults res = decomp.decompileFunction(fn, 120, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted()) {
                    pw.println(res.getDecompiledFunction().getC());
                }
                pw.println();
            }
        }
        println("Wrote " + touched.size() + " functions to " + outPath);
    }
}
