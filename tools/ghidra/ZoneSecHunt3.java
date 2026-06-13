// ZoneSecHunt3.java — final pin: writers of WorldClient staging fields and evaluator triggers.
// A) program-wide stores to [reg+0x150], [reg+0x154], [reg+0x158]
// B) stores to [reg+0x38] restricted to 0x4b0000..0x560000 (WorldClient/WWORLDMGR modules)
// C) decompile FUN_00559920 and FUN_004b9b20 (the two callers of evaluator FUN_004b9400)
// D) decompile FUN_0055aa30 region: already documented; skip. Instead decompile FUN_00558950 (world-load pump)
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.lang.OperandType;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class ZoneSecHunt3 extends GhidraScript {
    private static final String OUT = "/tmp/zonesec/client-static/zonesec_hunt3.txt";
    private DecompInterface dc;
    private Set<String> done = new HashSet<>();

    private void decomp(PrintWriter pw, long addr) {
        Function f = currentProgram.getFunctionManager().getFunctionAt(toAddr(addr));
        if (f == null) { pw.println("(no function at " + Long.toHexString(addr) + ")"); return; }
        if (done.contains(f.getName())) return;
        done.add(f.getName());
        pw.println("====== DECOMP " + f.getName() + " @ " + f.getEntryPoint() + " ======");
        DecompileResults r = dc.decompileFunction(f, 180, new ConsoleTaskMonitor());
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
            pw.println("##### A. stores to [reg+0x150/0x154/0x158] (program-wide) #####");
            pw.println("##### B. stores to [reg+0x38] in 0x4b0000..0x560000 #####");
            List<String> secA = new ArrayList<>(), secB = new ArrayList<>();
            InstructionIterator it = listing.getInstructions(true);
            while (it.hasNext()) {
                Instruction ins = it.next();
                String m = ins.getMnemonicString();
                if (!m.startsWith("MOV")) continue;
                if (ins.getNumOperands() < 2) continue;
                if ((ins.getOperandType(0) & OperandType.DYNAMIC) == 0) continue;
                long disp = -1; int nScal = 0;
                for (Object o : ins.getOpObjects(0))
                    if (o instanceof Scalar) { nScal++; disp = ((Scalar) o).getUnsignedValue(); }
                if (nScal != 1) continue;
                long a = ins.getAddress().getOffset();
                if (disp == 0x150 || disp == 0x154 || disp == 0x158) {
                    Function f = fm.getFunctionContaining(ins.getAddress());
                    secA.add(String.format("  %08x  disp=0x%x  %s   in %s", a, disp, ins,
                            f != null ? f.getName() : "?"));
                } else if (disp == 0x38 && a >= 0x4b0000 && a < 0x560000) {
                    Function f = fm.getFunctionContaining(ins.getAddress());
                    secB.add(String.format("  %08x  %s   in %s", a, ins,
                            f != null ? f.getName() : "?"));
                }
            }
            pw.println("---- A ----");
            for (String s : secA) pw.println(s);
            pw.println("---- B ----");
            for (String s : secB) pw.println(s);
            pw.println();

            pw.println("##### C. evaluator callers #####");
            decomp(pw, 0x559920);
            decomp(pw, 0x4b9b20);
        }
        println("Wrote " + OUT);
    }
}
