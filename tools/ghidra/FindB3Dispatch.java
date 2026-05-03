// FindB3Dispatch.java — locate code that fires event 0xb3 on
// FULLCHARSYSTEM. Pattern: an instruction that uses 0xb3 followed by
// an INDIRECT CALL within the same basic block (typical vtable[i]()
// pattern). We additionally check that the call target is a register
// load + offset access, not a direct call.

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.scalar.Scalar;

import java.io.PrintWriter;

public class FindB3Dispatch extends GhidraScript {

    private static final long TARGET = 0xb3L;
    private static final int CALL_LOOKAHEAD = 30;  // wide window — vtable patterns can spread

    private static final String OUT =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/event_b3_dispatch.txt";

    @Override
    protected void run() throws Exception {
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();

        try (PrintWriter pw = new PrintWriter(OUT)) {
            pw.println("# Code that uses 0xb3 followed by an indirect call within "
                    + CALL_LOOKAHEAD + " instructions");
            pw.println();

            FunctionIterator fit = fm.getFunctions(true);
            while (fit.hasNext()) {
                Function fn = fit.next();
                InstructionIterator iit = listing.getInstructions(fn.getBody(), true);
                Instruction prev = null;
                java.util.ArrayList<Instruction> recent = new java.util.ArrayList<>();
                Address b3At = null;
                int b3Countdown = 0;
                while (iit.hasNext()) {
                    Instruction instr = iit.next();
                    boolean has0xb3 = false;
                    int numOps = instr.getNumOperands();
                    for (int i = 0; i < numOps && !has0xb3; i++) {
                        Object[] rep = instr.getOpObjects(i);
                        for (Object o : rep) {
                            if (o instanceof Scalar) {
                                long v = ((Scalar) o).getUnsignedValue();
                                if (v == TARGET) {
                                    has0xb3 = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (has0xb3) {
                        b3At = instr.getAddress();
                        b3Countdown = CALL_LOOKAHEAD;
                    }
                    if (b3Countdown > 0) {
                        String mnem = instr.getMnemonicString();
                        if ("CALL".equals(mnem) || "CALLF".equals(mnem)) {
                            // Indirect call detection: the operand is "[reg+disp]"
                            // not a function symbol. Heuristic: stringify operand.
                            String op = instr.getDefaultOperandRepresentation(0);
                            if (op != null && (op.contains("[") || op.contains("dword"))) {
                                pw.println(String.format("HIT: 0xb3@%s -> CALL@%s in %s @ %s",
                                        b3At, instr.getAddress(),
                                        fn.getName(), fn.getEntryPoint()));
                                pw.println("  call op: " + op);
                            }
                        }
                        b3Countdown--;
                    }
                }
            }
        }
        println("wrote " + OUT);
    }
}
