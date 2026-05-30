// REHashFunctions.java — RE the auth packet integrity functions.
// 1) thunk_FUN_00458050 → resolve target + decompile
// 2) thunk_FUN_005f2f30 → resolve target + decompile
// 3) Dump raw bytes 0x75de37..0x75de4f (XOR loop)
// 4) Dump caller FUN_0075d660 around the local_4d8 init

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressFactory;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;

public class REHashFunctions extends GhidraScript {

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/nc2-bot/docs/hash_functions_decomp.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        Memory mem = currentProgram.getMemory();
        AddressFactory af = currentProgram.getAddressFactory();

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            // 1) Dump raw bytes around the XOR loop in FUN_0075d660
            pw.println("======================================================");
            pw.println("RAW BYTES 0x0075de00..0x0075de80 (XOR loop region)");
            pw.println("======================================================");
            Address start = af.getAddress("0075de00");
            for (long off = 0; off < 0x80; off += 0x10) {
                Address a = start.add(off);
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("%08x: ", a.getOffset()));
                for (int i = 0; i < 0x10; i++) {
                    sb.append(String.format("%02x ", mem.getByte(a.add(i)) & 0xff));
                }
                pw.println(sb.toString());
            }
            pw.println();

            // 2) Disassemble instructions in that range
            pw.println("======================================================");
            pw.println("DISASSEMBLY 0x0075de00..0x0075de80");
            pw.println("======================================================");
            InstructionIterator iit = listing.getInstructions(
                af.getAddress("0075de00"), true);
            while (iit.hasNext()) {
                Instruction ins = iit.next();
                if (ins.getAddress().getOffset() >= 0x75de80) break;
                pw.println(String.format("%08x: %s",
                    ins.getAddress().getOffset(), ins.toString()));
            }
            pw.println();

            // 3) Resolve thunk_FUN_00458050 target
            pw.println("======================================================");
            pw.println("thunk_FUN_00458050 @ 0x00458050");
            pw.println("======================================================");
            decompileAt(pw, decomp, listing, af, "00458050");

            // 4) Resolve thunk_FUN_005f2f30 target
            pw.println("======================================================");
            pw.println("thunk_FUN_005f2f30 @ 0x005f2f30");
            pw.println("======================================================");
            decompileAt(pw, decomp, listing, af, "005f2f30");

            // 5) FUN_0075d660 full decompile (so we can search for local_4d8 init)
            pw.println("======================================================");
            pw.println("FUN_0075d660 (caller) full decompile");
            pw.println("======================================================");
            decompileAt(pw, decomp, listing, af, "0075d660");
        }
        println("Wrote findings to " + OUT_PATH);
    }

    private void decompileAt(PrintWriter pw, DecompInterface decomp,
                              Listing listing, AddressFactory af, String hex)
            throws Exception {
        Address addr = af.getAddress(hex);
        Function fn = listing.getFunctionAt(addr);
        if (fn == null) {
            pw.println("(no function at " + hex + ")");
            return;
        }
        DecompileResults res = decomp.decompileFunction(
            fn, 240, new ConsoleTaskMonitor());
        if (res != null && res.decompileCompleted()) {
            pw.println(res.getDecompiledFunction().getC());
        } else {
            pw.println("(decompile failed)");
        }
        pw.println();
    }
}
