// FindByteSeq.java — find addresses where the byte sequence 84 80 appears as
// adjacent immediates in MOV instructions (auth packet write).
// Also finds functions that contain WSASend/send/sendto with adjacent setup
// that writes 0x84,0x80 to a buffer.

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.lang.OperandType;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;

import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;

public class FindByteSeq extends GhidraScript {

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/nc2-bot/docs/byte_seq_84_80.txt";

    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        Memory mem = currentProgram.getMemory();

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Instructions whose immediate operands include 0x80 then 0x84");
            pw.println("# (or 0x8084 stored as half-word) — TCP Auth packet header writes");
            pw.println();

            // Iterate instructions and look for MOV byte ptr [...], 0x84 or 0x80.
            // The packet header is `84 80 ...` little-endian on disk; in memory writes,
            // we'd see two adjacent MOV byte/word with these immediates, or a single
            // MOV word [ebp-?], 0x8084.
            InstructionIterator it = listing.getInstructions(true);
            Instruction prev = null;
            Set<Function> hits = new LinkedHashSet<>();
            while (it.hasNext()) {
                Instruction insn = it.next();
                String mnem = insn.getMnemonicString();
                if (!mnem.equals("MOV")) {
                    prev = insn;
                    continue;
                }
                // Inspect operands
                int numOps = insn.getNumOperands();
                for (int i = 0; i < numOps; i++) {
                    Scalar sc = insn.getScalar(i);
                    if (sc == null) continue;
                    long v = sc.getUnsignedValue();
                    // Match 0x8084 (word), 0x84 paired with 0x80, etc.
                    if (v == 0x8084L) {
                        Function fn = listing.getFunctionContaining(insn.getAddress());
                        pw.println("WORD 0x8084 @ " + insn.getAddress() + " in "
                                + (fn != null ? fn.getName() + " @ " + fn.getEntryPoint() : "?"));
                        if (fn != null) hits.add(fn);
                    } else if (v == 0x84L && prev != null && prev.getMnemonicString().equals("MOV")) {
                        // Check if prev had immediate 0x80 nearby
                        for (int j = 0; j < prev.getNumOperands(); j++) {
                            Scalar sp = prev.getScalar(j);
                            if (sp != null && sp.getUnsignedValue() == 0x80L) {
                                Function fn = listing.getFunctionContaining(insn.getAddress());
                                pw.println("MOV pair 0x80,0x84 @ " + prev.getAddress()
                                        + "/" + insn.getAddress() + " in "
                                        + (fn != null ? fn.getName() + " @ " + fn.getEntryPoint() : "?"));
                                if (fn != null) hits.add(fn);
                            }
                        }
                    } else if (v == 0x80L && prev != null && prev.getMnemonicString().equals("MOV")) {
                        for (int j = 0; j < prev.getNumOperands(); j++) {
                            Scalar sp = prev.getScalar(j);
                            if (sp != null && sp.getUnsignedValue() == 0x84L) {
                                Function fn = listing.getFunctionContaining(insn.getAddress());
                                pw.println("MOV pair 0x84,0x80 @ " + prev.getAddress()
                                        + "/" + insn.getAddress() + " in "
                                        + (fn != null ? fn.getName() + " @ " + fn.getEntryPoint() : "?"));
                                if (fn != null) hits.add(fn);
                            }
                        }
                    }
                }
                prev = insn;
            }

            pw.println();
            pw.println("# distinct functions containing 0x84/0x80 immediate writes:");
            for (Function f : hits) {
                pw.println("  " + f.getName() + " @ " + f.getEntryPoint());
            }
        }
        println("done -> " + OUT_PATH);
    }
}
