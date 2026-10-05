// DecompStringCallers.java — Ghidra headless script.
//
// Generic "who logs this?" probe: find every function referencing a string
// that contains one of the given needles, decompile it, then walk its callers
// (thunk-transparent) up to DEPTH levels and decompile those too. Prints the
// call chains so the packet handler that ends in the log line is visible.
//
// Usage (args: <out-file> <needle> [<needle> ...]):
//   /opt/ghidra/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient_x64.exe -noanalysis -readOnly \
//       -scriptPath /home/javier/Documents/Projects/Neocron/ceres-j/tools/ghidra \
//       -postScript DecompStringCallers.java /tmp/out.txt "Character Type not found"

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
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.*;

public class DecompStringCallers extends GhidraScript {

    private static final int DEPTH = 3;
    private static final int MAX_DECOMP_PER_LEVEL = 12;

    private DecompInterface decomp;

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 2) {
            println("usage: DecompStringCallers.java <out-file> <needle> [...]");
            return;
        }
        String outPath = args[0];
        String[] needles = Arrays.copyOfRange(args, 1, args.length);

        decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        try (PrintWriter pw = new PrintWriter(outPath)) {
            pw.println("# program " + currentProgram.getName()
                    + " sha256=" + currentProgram.getExecutableSHA256()
                    + " base=" + currentProgram.getImageBase());
            pw.println("# needles " + Arrays.toString(needles));

            LinkedHashMap<Address, String> hits = new LinkedHashMap<>();
            DataIterator dit = listing.getDefinedData(true);
            while (dit.hasNext()) {
                Data d = dit.next();
                Object val = d.getValue();
                if (!(val instanceof String)) continue;
                String s = (String) val;
                for (String n : needles) {
                    if (s.contains(n)) hits.put(d.getAddress(), s);
                }
            }
            pw.println("# " + hits.size() + " matching strings");

            LinkedHashSet<Function> level = new LinkedHashSet<>();
            for (Map.Entry<Address, String> e : hits.entrySet()) {
                pw.println("STRING @ " + e.getKey() + " = \"" + e.getValue() + "\"");
                for (Reference r : refMgr.getReferencesTo(e.getKey())) {
                    Function fn = listing.getFunctionContaining(r.getFromAddress());
                    pw.println("  ref " + r.getFromAddress() + " in "
                            + (fn == null ? "<no function>" : fn.getName() + " @ " + fn.getEntryPoint()));
                    if (fn != null) level.add(fn);
                }
            }

            Set<Function> seen = new HashSet<>();
            for (int depth = 0; depth <= DEPTH && !level.isEmpty(); depth++) {
                pw.println();
                pw.println("################ DEPTH " + depth + " (" + level.size() + " functions)");
                LinkedHashSet<Function> next = new LinkedHashSet<>();
                int n = 0;
                for (Function fn : level) {
                    if (!seen.add(fn)) continue;
                    Set<Function> callers = realCallers(fn);
                    pw.println("======================================================");
                    pw.println("FUNCTION " + fn.getName() + " @ " + fn.getEntryPoint()
                            + " (size " + fn.getBody().getNumAddresses() + ")");
                    StringBuilder cs = new StringBuilder();
                    for (Function c : callers) cs.append(' ').append(c.getName());
                    pw.println("callers:" + cs);
                    pw.println("======================================================");
                    if (n++ < MAX_DECOMP_PER_LEVEL) {
                        DecompileResults res = decomp.decompileFunction(fn, 180, new ConsoleTaskMonitor());
                        pw.println(res != null && res.decompileCompleted()
                                ? res.getDecompiledFunction().getC() : "(decompile failed)");
                    } else {
                        pw.println("(decompile skipped: level cap)");
                    }
                    next.addAll(callers);
                }
                level = next;
            }
        }
        println("wrote " + outPath);
    }

    // Callers of fn, walking backward through thunks to the real code.
    private Set<Function> realCallers(Function fn) {
        LinkedHashSet<Function> out = new LinkedHashSet<>();
        Deque<Function> work = new ArrayDeque<>();
        Set<Function> visited = new HashSet<>();
        work.add(fn);
        while (!work.isEmpty()) {
            Function f = work.poll();
            if (!visited.add(f)) continue;
            for (Reference r : currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint())) {
                if (!r.getReferenceType().isCall() && !r.getReferenceType().isJump()) continue;
                Function c = currentProgram.getListing().getFunctionContaining(r.getFromAddress());
                if (c == null) continue;
                if (c.isThunk()) work.add(c); else out.add(c);
            }
        }
        return out;
    }
}
