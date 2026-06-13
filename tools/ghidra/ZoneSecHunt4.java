// ZoneSecHunt4.java — final loose ends.
// A) disassemble 0x00702dc0..0x00703070 (around the [ESI+0x14] store at 0x702f76 in FUN_006ff980)
// B) find tiny getters returning [this+0x38] in 0x550000..0x560000 and xrefs to them
// C) all refs to RPOS ctor FUN_005b66c0 incl. thunks
// D) disassemble 0x0055a040..0x0055a0a0 (case 0x20 region, exact wire offsets)
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class ZoneSecHunt4 extends GhidraScript {
    private static final String OUT = "/tmp/zonesec/client-static/zonesec_hunt4.txt";

    private void disasmRange(PrintWriter pw, long lo, long hi) {
        Listing l = currentProgram.getListing();
        Instruction ins = l.getInstructionAt(toAddr(lo));
        if (ins == null) ins = l.getInstructionAfter(toAddr(lo));
        while (ins != null && ins.getAddress().getOffset() < hi) {
            pw.println("  " + ins.getAddress() + "  " + ins);
            ins = ins.getNext();
        }
    }

    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        try (PrintWriter pw = new PrintWriter(OUT)) {
            pw.println("##### A. disasm 00702dc0..00703080 (RPOS painter writes ctx+0x14) #####");
            disasmRange(pw, 0x702dc0, 0x703080);
            pw.println();

            pw.println("##### D. disasm 0055a020..0055a0b0 (case 0x19/0x20 handler) #####");
            disasmRange(pw, 0x55a020, 0x55a0b0);
            pw.println();

            pw.println("##### B. tiny getters of [this+0x38] in 0x550000..0x560000 #####");
            InstructionIterator it = listing.getInstructions(true);
            List<Address> getters = new ArrayList<>();
            while (it.hasNext()) {
                Instruction ins = it.next();
                long a = ins.getAddress().getOffset();
                if (a < 0x550000 || a >= 0x560000) continue;
                String s = ins.toString();
                if (!s.startsWith("MOV EAX,dword ptr [")) continue;
                boolean has38 = false;
                for (Object o : ins.getOpObjects(1))
                    if (o instanceof Scalar && ((Scalar) o).getUnsignedValue() == 0x38) has38 = true;
                if (!has38) continue;
                Instruction nx = ins.getNext();
                if (nx != null && nx.getMnemonicString().startsWith("RET")) {
                    Function f = fm.getFunctionContaining(ins.getAddress());
                    pw.println("  getter " + ins.getAddress() + "  " + ins + "  in "
                            + (f != null ? f.getName() : "?"));
                    if (f != null) {
                        for (Reference r : getReferencesTo(f.getEntryPoint())) {
                            Function c = fm.getFunctionContaining(r.getFromAddress());
                            pw.println("      ref " + r.getFromAddress() + " (" + r.getReferenceType()
                                    + ") in " + (c != null ? c.getName() : "?"));
                        }
                    }
                }
            }
            pw.println();

            pw.println("##### C. refs to FUN_005b66c0 (any type) #####");
            for (Reference r : getReferencesTo(toAddr(0x5b66c0))) {
                Function f = fm.getFunctionContaining(r.getFromAddress());
                pw.println("  " + r.getFromAddress() + " (" + r.getReferenceType() + ") in "
                        + (f != null ? f.getName() : "?"));
                if (f != null && r.getReferenceType().toString().contains("JUMP")) {
                    for (Reference r2 : getReferencesTo(f.getEntryPoint())) {
                        Function c2 = fm.getFunctionContaining(r2.getFromAddress());
                        pw.println("     thunk-ref " + r2.getFromAddress() + " ("
                                + r2.getReferenceType() + ") in " + (c2 != null ? c2.getName() : "?"));
                    }
                }
            }
        }
        println("Wrote " + OUT);
    }
}
