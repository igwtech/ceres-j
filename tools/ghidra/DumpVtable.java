// DumpVtable.java — dump N 4-byte pointers starting at an address, resolve to functions.
// args: outpath, startHex, count
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import java.io.PrintWriter;

public class DumpVtable extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        String outPath = args[0];
        Address start = currentProgram.getAddressFactory().getAddress(args[1]);
        int n = Integer.parseInt(args[2]);
        Memory mem = currentProgram.getMemory();
        Listing listing = currentProgram.getListing();
        try (PrintWriter pw = new PrintWriter(outPath)) {
            for (int i = 0; i < n; i++) {
                Address slot = start.add(i * 4L);
                int p = mem.getInt(slot);
                long pv = ((long)p) & 0xffffffffL;
                Address tgt = currentProgram.getAddressFactory().getAddress(Long.toHexString(pv));
                Function f = listing.getFunctionContaining(tgt);
                pw.println("[" + i + "] @"+slot+" -> 0x" + Long.toHexString(pv) + "  " + (f!=null?f.getName():""));
            }
        }
        println("Wrote " + outPath);
    }
}
