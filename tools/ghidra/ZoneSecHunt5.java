// ZoneSecHunt5.java — closure checks.
// A) disasm 0x55ee70..0x55eed0 (byte store [EBX+0x38] in UDP dispatcher FUN_0055ec10)
// B) decompile snippet of FUN_00649a50 around the RPOS ctor call at 0064a060 (disasm window)
// C) decompile FUN_004b96b0 (default handler of FUN_00559920) header to see its switch cases briefly
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class ZoneSecHunt5 extends GhidraScript {
    private static final String OUT = "/tmp/zonesec/client-static/zonesec_hunt5.txt";

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
        DecompInterface dc = new DecompInterface();
        dc.setOptions(new DecompileOptions());
        dc.openProgram(currentProgram);
        FunctionManager fm = currentProgram.getFunctionManager();
        try (PrintWriter pw = new PrintWriter(OUT)) {
            pw.println("##### A. disasm 0055ee70..0055eee0 #####");
            disasmRange(pw, 0x55ee70, 0x55eee0);
            pw.println();
            pw.println("##### B. disasm 0064a020..0064a0a0 (RPOS ctor call in FUN_00649a50) #####");
            disasmRange(pw, 0x64a020, 0x64a0a0);
            pw.println();
            pw.println("##### C. FUN_004b96b0 decompile #####");
            Function f = fm.getFunctionAt(toAddr(0x4b96b0));
            if (f != null) {
                DecompileResults r = dc.decompileFunction(f, 150, new ConsoleTaskMonitor());
                if (r != null && r.decompileCompleted())
                    pw.println(r.getDecompiledFunction().getC());
            }
        }
        println("Wrote " + OUT);
    }
}
