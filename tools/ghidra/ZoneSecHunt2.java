// ZoneSecHunt2.java — pin the writers/callers of the zone-security evaluator FUN_004b9400.
// A) all refs to 004b9400 + thunks to it + refs to those thunks
// B) program-wide stores to [reg+0x18c]; list fn + instruction (world-id field writer hunt)
// C) decompile FUN_004b36e0 (worldinfo col getter), FUN_004b3820, FUN_004b3e70 (col setter)
// D) dump dword table at 00a18670..00a18900 (apartment id remap)
// E) decompile any function that BOTH stores [reg+0x18c] and [reg+0x38]
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.program.model.lang.OperandType;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class ZoneSecHunt2 extends GhidraScript {
    private static final String OUT = "/tmp/zonesec/client-static/zonesec_hunt2.txt";
    private DecompInterface dc;
    private Set<String> done = new HashSet<>();

    private void decomp(PrintWriter pw, Function f) {
        if (f == null || done.contains(f.getName())) return;
        done.add(f.getName());
        pw.println("====== DECOMP " + f.getName() + " @ " + f.getEntryPoint() + " ======");
        DecompileResults r = dc.decompileFunction(f, 150, new ConsoleTaskMonitor());
        if (r != null && r.decompileCompleted()) pw.println(r.getDecompiledFunction().getC());
        else pw.println("(decompile failed)");
        pw.println();
    }

    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        dc = new DecompInterface();
        dc.setOptions(new DecompileOptions());
        dc.openProgram(currentProgram);

        try (PrintWriter pw = new PrintWriter(OUT)) {
            Address tgt = toAddr(0x4b9400);
            pw.println("##### A. refs to FUN_004b9400 #####");
            Set<Address> thunkAddrs = new LinkedHashSet<>();
            for (Reference r : getReferencesTo(tgt)) {
                Function f = fm.getFunctionContaining(r.getFromAddress());
                pw.println("  " + r.getFromAddress() + " (" + r.getReferenceType() + ") in "
                        + (f != null ? f.getName() : "?"));
                if (f != null && f.getName().startsWith("thunk")) thunkAddrs.add(f.getEntryPoint());
                // a JMP-only stub may not be named thunk_; treat 1-insn fns as thunks
                if (f != null && r.getReferenceType().toString().contains("JUMP"))
                    thunkAddrs.add(f.getEntryPoint());
            }
            for (Address ta : thunkAddrs) {
                pw.println("  -- refs to thunk @ " + ta + ":");
                for (Reference r : getReferencesTo(ta)) {
                    Function f = fm.getFunctionContaining(r.getFromAddress());
                    pw.println("     " + r.getFromAddress() + " (" + r.getReferenceType() + ") in "
                            + (f != null ? f.getName() : "?"));
                }
            }
            pw.println();

            pw.println("##### B. program-wide stores to [reg+0x18c] #####");
            Map<Function, List<String>> w18c = new LinkedHashMap<>();
            Map<Function, List<String>> w38 = new LinkedHashMap<>();
            InstructionIterator it = listing.getInstructions(true);
            while (it.hasNext()) {
                Instruction ins = it.next();
                String m = ins.getMnemonicString();
                if (!m.startsWith("MOV")) continue;
                if (ins.getNumOperands() < 2) continue;
                if ((ins.getOperandType(0) & OperandType.DYNAMIC) == 0) continue;
                long disp = -1;
                Object[] objs = ins.getOpObjects(0);
                // only [reg + imm] (2 objects: register + scalar)
                int nScal = 0;
                for (Object o : objs)
                    if (o instanceof Scalar) { nScal++; disp = ((Scalar) o).getUnsignedValue(); }
                if (nScal != 1) continue;
                if (disp == 0x18c) {
                    Function f = fm.getFunctionContaining(ins.getAddress());
                    w18c.computeIfAbsent(f, k -> new ArrayList<>())
                        .add(ins.getAddress() + "  " + ins);
                } else if (disp == 0x38) {
                    Function f = fm.getFunctionContaining(ins.getAddress());
                    w38.computeIfAbsent(f, k -> new ArrayList<>())
                        .add(ins.getAddress() + "  " + ins);
                }
            }
            for (Map.Entry<Function, List<String>> e : w18c.entrySet()) {
                pw.println("  fn " + (e.getKey() != null ? e.getKey().getName() : "?"));
                for (String s : e.getValue()) pw.println("     " + s);
            }
            pw.println();

            pw.println("##### E. functions storing BOTH +0x18c and +0x38 #####");
            List<Function> both = new ArrayList<>();
            for (Function f : w18c.keySet())
                if (f != null && w38.containsKey(f)) both.add(f);
            for (Function f : both) {
                pw.println("  BOTH: " + f.getName() + " @ " + f.getEntryPoint());
                for (String s : w38.get(f)) pw.println("     [0x38] " + s);
            }
            pw.println();

            pw.println("##### C. table accessors #####");
            decomp(pw, fm.getFunctionAt(toAddr(0x4b36e0)));
            decomp(pw, fm.getFunctionAt(toAddr(0x4b3820)));
            decomp(pw, fm.getFunctionAt(toAddr(0x4b3e70)));

            pw.println("##### D. dword table 00a18670..00a18900 #####");
            for (long a = 0xa18670; a < 0xa18900; a += 4) {
                int v = currentProgram.getMemory().getInt(toAddr(a));
                pw.printf("  %08x: %d (0x%x)%n", a, v, v);
            }
            pw.println();

            pw.println("##### E2. decompiles of BOTH-writers (cap 8) #####");
            int cap = 0;
            for (Function f : both) {
                if (cap++ > 7) break;
                decomp(pw, f);
            }
        }
        println("Wrote " + OUT);
    }
}
