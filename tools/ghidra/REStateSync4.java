// REStateSync4.java — message dequeue + SCRIPTEDPLAYER vtable + WA-type
// registration, to settle: which byte is the WWORLDMGR Type, and which
// opcode reaches the SCRIPTEDPLAYER ctor (Type 15 vs reliable 0x28).

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class REStateSync4 extends GhidraScript {

    private static final String[] ADDRS = {
        "004b8cd0",  // message dequeue -> 12B descriptor {size, ptr, channel}
        "004b9620",  // upstream of FUN_004b8fb0 (decode loop start)
        "0069f460",  // SCRIPTEDPLAYER post-ctor (param_4 handler in 0069a580)
        "006a06e0",  // SCRIPTEDPLAYER finalize
        "00540cb0",  // del-player path
        "0069cb50",  // SCRIPTEDPLAYER alt path when in_ECX[7]+0x29==0
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump4.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        Listing listing = currentProgram.getListing();
        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            // dump SCRIPTEDPLAYER vtable layout (slots 0x18, 0x20, 0x28)
            pw.println("##### SCRIPTEDPLAYER::vftable symbols #####");
            SymbolIterator si = currentProgram.getSymbolTable()
                    .getSymbolIterator("SCRIPTEDPLAYER*", true);
            while (si.hasNext()) {
                Symbol s = si.next();
                pw.println("  " + s.getName() + " @ " + s.getAddress());
                if (s.getName().contains("vftable")) {
                    Address va = s.getAddress();
                    for (int i = 0; i < 0x40; i += 4) {
                        Data d = listing.getDataAt(va.add(i));
                        long v = 0;
                        try {
                            v = currentProgram.getMemory()
                                  .getInt(va.add(i)) & 0xffffffffL;
                        } catch (Exception e) {}
                        Function tf = listing.getFunctionAt(
                                currentProgram.getAddressFactory()
                                  .getDefaultAddressSpace().getAddress(v));
                        pw.println(String.format(
                            "    [+0x%02x] -> %08x %s", i, v,
                            tf != null ? tf.getName() : ""));
                    }
                }
            }
            pw.println();

            for (String hex : ADDRS) {
                Address addr = currentProgram.getAddressFactory().getAddress(hex);
                Function fn = listing.getFunctionAt(addr);
                String name = fn != null ? fn.getName() : ("FUN_" + hex);
                pw.println("======================================================");
                pw.println("FUNCTION " + name + " @ " + addr);
                pw.println("======================================================");
                if (fn != null) {
                    DecompileResults res = decomp.decompileFunction(
                            fn, 200, new ConsoleTaskMonitor());
                    if (res != null && res.decompileCompleted())
                        pw.println(res.getDecompiledFunction().getC());
                    else
                        pw.println("(decompile failed)");
                } else {
                    pw.println("(no function at this address)");
                }
                pw.println();
            }
        }
        println("Wrote vtable + " + ADDRS.length + " functions to " + OUT_PATH);
    }
}
