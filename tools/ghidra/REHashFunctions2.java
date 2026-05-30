// REHashFunctions2.java — extended dump
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
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;

public class REHashFunctions2 extends GhidraScript {

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/nc2-bot/docs/hash_functions_decomp2.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        Memory mem = currentProgram.getMemory();
        AddressFactory af = currentProgram.getAddressFactory();

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            // Extended raw bytes + disasm 0x75de70..0x75df80
            pw.println("=== RAW BYTES 0x0075de70..0x0075dfa0 ===");
            Address start = af.getAddress("0075de70");
            for (long off = 0; off < 0x140; off += 0x10) {
                Address a = start.add(off);
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("%08x: ", a.getOffset()));
                for (int i = 0; i < 0x10; i++) {
                    sb.append(String.format("%02x ", mem.getByte(a.add(i)) & 0xff));
                }
                pw.println(sb.toString());
            }
            pw.println();

            pw.println("=== DISASM 0x0075de70..0x0075dfa0 ===");
            InstructionIterator iit = listing.getInstructions(
                af.getAddress("0075de70"), true);
            while (iit.hasNext()) {
                Instruction ins = iit.next();
                if (ins.getAddress().getOffset() >= 0x75dfa0) break;
                pw.println(String.format("%08x: %s",
                    ins.getAddress().getOffset(), ins.toString()));
            }
            pw.println();

            // thunk_FUN_00465fb0 — what FUN_00458050 calls to set up file path/buffer
            pw.println("=== thunk_FUN_00465fb0 @ 0x00465fb0 ===");
            decompileAt(pw, decomp, listing, af, "00465fb0");

            // thunk_FUN_00466130 — does the file read/processing
            pw.println("=== thunk_FUN_00466130 @ 0x00466130 ===");
            decompileAt(pw, decomp, listing, af, "00466130");

            // thunk_FUN_00465fe0 — called when cVar2 != 0
            pw.println("=== thunk_FUN_00465fe0 @ 0x00465fe0 ===");
            decompileAt(pw, decomp, listing, af, "00465fe0");

            // thunk_FUN_00466100 — writes param_2
            pw.println("=== thunk_FUN_00466100 @ 0x00466100 ===");
            decompileAt(pw, decomp, listing, af, "00466100");

            // thunk_FUN_004665b0 — cleanup
            pw.println("=== thunk_FUN_004665b0 @ 0x004665b0 ===");
            decompileAt(pw, decomp, listing, af, "004665b0");

            // thunk_FUN_00476790 — for FUN_005f2f30
            pw.println("=== thunk_FUN_00476790 @ 0x00476790 ===");
            decompileAt(pw, decomp, listing, af, "00476790");

            // Find callers of FUN_005f2f30 to understand the context
            pw.println("=== Callers of 0x005f2f30 ===");
            Address f30 = af.getAddress("005f2f30");
            ReferenceManager rm = currentProgram.getReferenceManager();
            for (Reference ref : rm.getReferencesTo(f30)) {
                pw.println(ref.getFromAddress() + " -> " + f30 + " (" + ref.getReferenceType() + ")");
            }
            pw.println();

            // local_4d8 init in FUN_0075d660 — find by searching listing
            // Frame layout: local_4d8 at EBP-0x4d8
            // Let's find any LEA referencing [EBP+0xfffffb28] = [EBP-0x4d8]
            pw.println("=== Instructions in FUN_0075d660 referencing local_4d8 (EBP-0x4d8) ===");
            InstructionIterator it2 = listing.getInstructions(
                af.getAddress("0075d660"), true);
            while (it2.hasNext()) {
                Instruction ins = it2.next();
                if (ins.getAddress().getOffset() >= 0x75e500) break;
                String s = ins.toString();
                // EBP-0x4d8 = EBP+0xfffffb28
                if (s.contains("fffffb28") || s.contains("FFFFFB28")) {
                    pw.println(String.format("%08x: %s",
                        ins.getAddress().getOffset(), s));
                }
            }
            pw.println();
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
