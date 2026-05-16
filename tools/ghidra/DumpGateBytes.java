// DumpGateBytes.java — dump the machine-code bytes of the log-sink/gate
// function FUN_004471c0 (the prefix-dispatch + DAT_00b05078 comparison
// region) so the same code can be located by signature in a different
// build of neocronclient.exe (the on-disk binary differs from the one
// imported into Ghidra).

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;

public class DumpGateBytes extends GhidraScript {
    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        Address ep = currentProgram.getAddressFactory().getAddress("004471c0");
        Function fn = listing.getFunctionAt(ep);
        println("Function " + (fn != null ? fn.getName() : "?") + " @ " + ep);

        // Dump first ~0xB0 bytes (covers prefix dispatch through the
        // DAT_00b05078 compares and the atoi level-set).
        Address end = ep.add(0xC0);
        StringBuilder hex = new StringBuilder();
        Address a = ep;
        int col = 0;
        while (a.compareTo(end) < 0) {
            byte b = currentProgram.getMemory().getByte(a);
            hex.append(String.format("%02x ", b & 0xff));
            if (++col % 16 == 0) hex.append("\n");
            a = a.add(1);
        }
        println("RAW BYTES 004471c0 .. +0xC0:");
        println(hex.toString());

        // Disassembly with addresses + bytes so we can see exactly which
        // instruction tests/loads DAT_00b05078 (the gate) and the atoi set.
        println("DISASSEMBLY:");
        Instruction ins = listing.getInstructionAt(ep);
        while (ins != null && ins.getAddress().compareTo(end) < 0) {
            StringBuilder ib = new StringBuilder();
            try {
                byte[] bb = ins.getBytes();
                for (byte x : bb) ib.append(String.format("%02x", x & 0xff));
            } catch (Exception e) { ib.append("??"); }
            println(String.format("%s  %-20s  %s",
                    ins.getAddress(), ib.toString(), ins.toString()));
            ins = ins.getNext();
        }
    }
}
