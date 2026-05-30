import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressFactory;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.mem.Memory;

import java.io.PrintWriter;

public class REHashFunctions3 extends GhidraScript {

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/nc2-bot/docs/hash_functions_decomp3.txt";

    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        Memory mem = currentProgram.getMemory();
        AddressFactory af = currentProgram.getAddressFactory();

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("=== RAW BYTES 0x0075df80..0x0075e060 ===");
            Address start = af.getAddress("0075df80");
            for (long off = 0; off < 0xe0; off += 0x10) {
                Address a = start.add(off);
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("%08x: ", a.getOffset()));
                for (int i = 0; i < 0x10; i++) {
                    sb.append(String.format("%02x ", mem.getByte(a.add(i)) & 0xff));
                }
                pw.println(sb.toString());
            }
            pw.println();

            pw.println("=== DISASM 0x0075df80..0x0075e060 ===");
            InstructionIterator iit = listing.getInstructions(
                af.getAddress("0075df80"), true);
            while (iit.hasNext()) {
                Instruction ins = iit.next();
                if (ins.getAddress().getOffset() >= 0x75e060) break;
                pw.println(String.format("%08x: %s",
                    ins.getAddress().getOffset(), ins.toString()));
            }
        }
        println("done");
    }
}
