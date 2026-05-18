// DecompChatSend.java — decompile the chat-send helper FUN_006f9680
// and the property dispatcher around 0xfb1 (chat-channel selector)
// to byte-pin the /emote wire form (task #187).

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.util.task.ConsoleTaskMonitor;

public class DecompChatSend extends GhidraScript {
    static final String[] FUNCS = {
        "FUN_006f9680", "FUN_006f8930", "FUN_005578c0", "FUN_005ca550"
    };
    @Override public void run() throws Exception {
        DecompInterface dec = new DecompInterface();
        dec.setOptions(new DecompileOptions());
        dec.openProgram(currentProgram);
        for (String fn : FUNCS) {
            if (getGlobalFunctions(fn).isEmpty()) {
                println("MISSING " + fn);
                continue;
            }
            Function f = getGlobalFunctions(fn).get(0);
            println("==== DECOMPILE " + fn + " @ "
                    + f.getEntryPoint() + " ====");
            DecompileResults r =
                dec.decompileFunction(f, 90, new ConsoleTaskMonitor());
            if (r != null && r.decompileCompleted())
                println(r.getDecompiledFunction().getC());
            else println("  <failed>");
        }
        dec.dispose();
    }
}
