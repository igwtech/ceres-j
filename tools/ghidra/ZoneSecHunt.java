// ZoneSecHunt.java — batched hunt for the zone-security classification path.
// 1) scalar refs to 0xC80 (text id 3200 "Secure Sector...") and 0xC90 (3216 "Unknown world type!")
// 2) string refs to "setentry", "Current Sector:", "worldinfo"
// 3) decompile every hit function + 1 level of callers
// 4) callers of FUN_005b66c0 (RPOS ctor; DAT_011633b4 = its param_1)
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class ZoneSecHunt extends GhidraScript {
    private static final String OUT = "/tmp/zonesec/client-static/zonesec_hunt.txt";
    private DecompInterface dc;
    private Set<String> decompiled = new HashSet<>();

    private void decomp(PrintWriter pw, Function f) {
        if (f == null || decompiled.contains(f.getName())) return;
        decompiled.add(f.getName());
        pw.println("====== DECOMP " + f.getName() + " @ " + f.getEntryPoint() + " ======");
        DecompileResults res = dc.decompileFunction(f, 120, new ConsoleTaskMonitor());
        if (res != null && res.decompileCompleted())
            pw.println(res.getDecompiledFunction().getC());
        else pw.println("(decompile failed)");
        pw.println();
    }

    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        Memory mem = currentProgram.getMemory();
        dc = new DecompInterface();
        DecompileOptions opt = new DecompileOptions();
        dc.setOptions(opt);
        dc.openProgram(currentProgram);

        long[] scalars = new long[]{0xC80, 0xC90};
        Set<Long> targets = new HashSet<>();
        for (long s : scalars) targets.add(s);

        try (PrintWriter pw = new PrintWriter(OUT)) {
            // ---------- 1) scalar hunt ----------
            pw.println("##### A. instructions with imm 0xC80(3200) / 0xC90(3216) #####");
            Set<Function> scalarFns = new LinkedHashSet<>();
            InstructionIterator it = listing.getInstructions(true);
            while (it.hasNext()) {
                Instruction ins = it.next();
                for (int op = 0; op < ins.getNumOperands(); op++) {
                    for (Object o : ins.getOpObjects(op)) {
                        if (o instanceof Scalar) {
                            long v = ((Scalar) o).getUnsignedValue();
                            if (targets.contains(v)) {
                                Function f = fm.getFunctionContaining(ins.getAddress());
                                pw.println("  " + ins.getAddress() + "  imm=0x" + Long.toHexString(v)
                                        + "  in " + (f != null ? f.getName() : "?") + "  | " + ins);
                                if (f != null) scalarFns.add(f);
                            }
                        }
                    }
                }
            }
            pw.println();

            // ---------- 2) string refs ----------
            String[] needles = {"setentry", "Current Sector:", "worldinfo", "Unknown world type"};
            Map<String, Set<Function>> strFns = new LinkedHashMap<>();
            pw.println("##### B. string locations + refs #####");
            for (String needle : needles) {
                strFns.put(needle, new LinkedHashSet<>());
                byte[] pat = needle.getBytes("ASCII");
                Address a = mem.getMinAddress();
                while (a != null) {
                    Address found = mem.findBytes(a, pat, null, true, monitor);
                    if (found == null) break;
                    pw.println("  string \"" + needle + "\" @ " + found);
                    // refs to exact addr
                    for (Reference r : getReferencesTo(found)) {
                        Function f = fm.getFunctionContaining(r.getFromAddress());
                        pw.println("      ref " + r.getFromAddress() + " (" + r.getReferenceType()
                                + ") in " + (f != null ? f.getName() : "?"));
                        if (f != null) strFns.get(needle).add(f);
                    }
                    a = found.add(1);
                }
            }
            pw.println();

            // ---------- 3) decompile scalar-hit fns + 1 level of callers ----------
            pw.println("##### C. decompiles: scalar-hit functions #####");
            int cap = 0;
            for (Function f : scalarFns) {
                if (cap++ > 14) { pw.println("(cap reached)"); break; }
                decomp(pw, f);
            }
            pw.println("##### C2. callers of scalar-hit functions #####");
            for (Function f : scalarFns) {
                Set<Function> callers = f.getCallingFunctions(monitor);
                pw.println("  callers of " + f.getName() + ":");
                for (Function c : callers) pw.println("      " + c.getName() + " @ " + c.getEntryPoint());
            }
            pw.println();

            pw.println("##### D. decompiles: string-hit functions #####");
            for (Map.Entry<String, Set<Function>> e : strFns.entrySet()) {
                pw.println("---- needle: " + e.getKey() + " ----");
                int c2 = 0;
                for (Function f : e.getValue()) {
                    if (c2++ > 6) { pw.println("(cap reached)"); break; }
                    decomp(pw, f);
                }
                for (Function f : e.getValue()) {
                    Set<Function> callers = f.getCallingFunctions(monitor);
                    pw.println("  callers of " + f.getName() + ":");
                    for (Function c : callers) pw.println("      " + c.getName() + " @ " + c.getEntryPoint());
                }
            }
            pw.println();

            // ---------- 4) RPOS ctor callers ----------
            pw.println("##### E. callers of FUN_005b66c0 (RPOS ctor) #####");
            Address ctorA = currentProgram.getAddressFactory().getAddress("005b66c0");
            Function ctor = fm.getFunctionAt(ctorA);
            if (ctor != null) {
                Set<Function> callers = ctor.getCallingFunctions(monitor);
                for (Function c : callers) {
                    pw.println("  caller: " + c.getName() + " @ " + c.getEntryPoint());
                    decomp(pw, c);
                }
            } else pw.println("  (no function at 005b66c0)");
        }
        println("Wrote " + OUT);
    }
}
