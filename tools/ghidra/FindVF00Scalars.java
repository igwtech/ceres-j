// FindVF00Scalars.java — find code that compares the 4-char chunk tags as
// little-endian u32 immediates (the loader dispatch), and dump those funcs.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.OperandType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.TreeMap;

public class FindVF00Scalars extends GhidraScript {

    // little-endian u32 for each 4-char tag
    private static long le(String t) {
        return ((long)(t.charAt(0)&0xff))
             | ((long)(t.charAt(1)&0xff) << 8)
             | ((long)(t.charAt(2)&0xff) << 16)
             | ((long)(t.charAt(3)&0xff) << 24);
    }

    private static final String[] TAGS = {
        "VF00","ACTR","BOD^","BONE","SBKB","CSBK","Mate","Geom","Moti","Body","Head"
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/vf00_scalar_refs.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        Listing listing = currentProgram.getListing();

        // map scalar value -> tag
        TreeMap<Long,String> wanted = new TreeMap<>();
        for (String t : TAGS) {
            long v = le(t);
            wanted.put(v, t);
            println(String.format("tag %s -> 0x%08x", t, v));
        }

        TreeMap<Address, Function> touched = new TreeMap<>();
        TreeMap<Address, StringBuilder> why = new TreeMap<>();

        InstructionIterator it = listing.getInstructions(true);
        while (it.hasNext()) {
            Instruction ins = it.next();
            int n = ins.getNumOperands();
            for (int i = 0; i < n; i++) {
                Object[] objs = ins.getOpObjects(i);
                for (Object o : objs) {
                    if (o instanceof Scalar) {
                        long v = ((Scalar)o).getUnsignedValue() & 0xffffffffL;
                        String tag = wanted.get(v);
                        if (tag != null) {
                            Function fn = listing.getFunctionContaining(ins.getAddress());
                            if (fn != null) {
                                Address ep = fn.getEntryPoint();
                                touched.putIfAbsent(ep, fn);
                                why.computeIfAbsent(ep, k -> new StringBuilder())
                                   .append("  ").append(ins.getAddress())
                                   .append("  ").append(ins.toString())
                                   .append("   [tag ").append(tag).append("]\n");
                            }
                        }
                    }
                }
            }
        }

        println("=== " + touched.size() + " functions compare a chunk tag ===");

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Functions comparing VF00 chunk tags as u32 immediates");
            for (Function fn : touched.values()) {
                pw.println("======================================================");
                pw.println("FUNCTION " + fn.getName() + " @ " + fn.getEntryPoint());
                pw.println("======================================================");
                pw.print(why.get(fn.getEntryPoint()));
                pw.println();
                DecompileResults res = decomp.decompileFunction(
                        fn, 120, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted()) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("(decompile failed)");
                }
                pw.println();
            }
        }
        println("Wrote " + touched.size() + " functions to " + OUT_PATH);
    }
}
