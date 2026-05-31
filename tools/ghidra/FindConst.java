// FindConst.java — find functions that compare against a given scalar/byte (in cmp/switch).
// args: outpath, then hex scalar values to hunt as immediate operands.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import java.io.PrintWriter;
import java.util.*;

public class FindConst extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        String outPath = args[0];
        Set<Long> targets = new HashSet<>();
        for (int i = 1; i < args.length; i++) targets.add(Long.parseLong(args[i], 16));
        Listing listing = currentProgram.getListing();
        InstructionIterator it = listing.getInstructions(true);
        try (PrintWriter pw = new PrintWriter(outPath)) {
            while (it.hasNext()) {
                Instruction ins = it.next();
                String m = ins.getMnemonicString();
                if (!(m.equals("CMP") || m.startsWith("SUB") || m.equals("MOV") || m.startsWith("J") )) continue;
                for (int op = 0; op < ins.getNumOperands(); op++) {
                    Object[] objs = ins.getOpObjects(op);
                    for (Object o : objs) {
                        if (o instanceof Scalar) {
                            long v = ((Scalar)o).getUnsignedValue();
                            if (targets.contains(v)) {
                                Function f = listing.getFunctionContaining(ins.getAddress());
                                pw.println(ins.getAddress()+"  "+m+"  imm=0x"+Long.toHexString(v)+
                                    "  in "+(f!=null?f.getName():"?")+"  | "+ins.toString());
                            }
                        }
                    }
                }
            }
        }
        println("Wrote "+outPath);
    }
}
