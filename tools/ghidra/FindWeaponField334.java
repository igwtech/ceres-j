// FindWeaponField334.java — hunt the authoritative in-hand weapon field (+0x334)
// 1) every instruction that references displacement 0x334 (read vs write)
// 2) callers of the PlayerAction dispatcher FUN_0064ec90 (maps wire->dispatch)
// 3) decompile each distinct function that WRITES +0x334
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.OperandType;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class FindWeaponField334 extends GhidraScript {
    private static final long TARGET = 0x334;
    private static final String OUT =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/weapon_field_334.txt";

    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        Set<Function> writers = new LinkedHashSet<>();
        try (PrintWriter pw = new PrintWriter(OUT)) {
            pw.println("##### instructions referencing displacement 0x334 #####");
            InstructionIterator it = listing.getInstructions(true);
            while (it.hasNext()) {
                Instruction ins = it.next();
                boolean hit = false;
                for (int op = 0; op < ins.getNumOperands(); op++) {
                    for (Object o : ins.getOpObjects(op)) {
                        if (o instanceof Scalar && ((Scalar)o).getUnsignedValue() == TARGET) {
                            hit = true;
                        }
                    }
                }
                if (!hit) continue;
                Function f = fm.getFunctionContaining(ins.getAddress());
                String fn = f != null ? f.getName() : "?";
                // write heuristic: store mnemonic w/ memory dest operand 0
                String m = ins.getMnemonicString();
                boolean write = (m.equals("MOV") || m.startsWith("MOV") || m.equals("AND")
                        || m.equals("OR") || m.equals("ADD") || m.equals("SUB"))
                        && (ins.getOperandType(0) & OperandType.DYNAMIC) != 0;
                if (write && f != null) writers.add(f);
                pw.printf("  %s  %-30s  %s   [%s in %s]%n",
                    ins.getAddress(), ins.toString(), write ? "WRITE" : "read", m, fn);
            }

            pw.println();
            pw.println("##### callers of FUN_0064ec90 (PlayerAction dispatcher) #####");
            Function disp = fm.getFunctionAt(addr("0064ec90"));
            if (disp != null) {
                for (Reference r : getReferencesTo(disp.getEntryPoint())) {
                    Function c = fm.getFunctionContaining(r.getFromAddress());
                    pw.printf("  %s  from %s%n", r.getFromAddress(),
                        c != null ? c.getName() : "?");
                }
            } else pw.println("  (no function at 0064ec90)");

            // decompile writers of +0x334
            DecompInterface d = new DecompInterface();
            d.setOptions(new DecompileOptions());
            d.openProgram(currentProgram);
            pw.println();
            pw.println("##### decompiled functions that WRITE +0x334 #####");
            for (Function f : writers) {
                pw.println("====== " + f.getName() + " @ " + f.getEntryPoint() + " ======");
                DecompileResults res = d.decompileFunction(f, 120, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted())
                    pw.println(res.getDecompiledFunction().getC());
                else pw.println("(decompile failed)");
                pw.println();
            }
        }
        println("Wrote " + OUT + "  (writers=" + writers.size() + ")");
    }

    private Address addr(String h) {
        return currentProgram.getAddressFactory().getAddress(h);
    }
}
