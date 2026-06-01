// DecompSkelAnim.java — decompile bone/skin (SBKB/CSBK) + motion (Moti) readers.
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class DecompSkelAnim extends GhidraScript {
    static final long[] TAGS = {
        0x424b4253L, // "SBKB"
        0x4b425343L, // "CSBK"
        0x69746f4dL, // "Moti"
        0x6d6f6547L, // "Geom"
        0x79646f42L, // "Body"
        0x64616548L, // "Head"
    };
    static final String OUT = "/home/javier/Documents/Projects/Neocron/ceres-j/docs/skel_anim_decomp.txt";

    Set<Function> findFnsWithScalar(long val) {
        Set<Function> out = new HashSet<>();
        InstructionIterator it = currentProgram.getListing().getInstructions(true);
        while (it.hasNext()) {
            Instruction ins = it.next();
            for (int i = 0; i < ins.getNumOperands(); i++) {
                for (Object o : ins.getOpObjects(i)) {
                    if (o instanceof Scalar && ((Scalar)o).getUnsignedValue() == val) {
                        Function f = currentProgram.getFunctionManager().getFunctionContaining(ins.getAddress());
                        if (f != null) out.add(f);
                    }
                }
            }
        }
        return out;
    }

    @Override
    protected void run() throws Exception {
        DecompInterface d = new DecompInterface();
        d.setOptions(new DecompileOptions());
        d.openProgram(currentProgram);
        LinkedHashSet<Function> targets = new LinkedHashSet<>();
        // Always include the known SBKB reader
        Function known = getFunctionAt(currentProgram.getAddressFactory().getAddress("005ac270"));
        if (known != null) targets.add(known);
        try (PrintWriter pw = new PrintWriter(OUT)) {
            for (long t : TAGS) {
                Set<Function> fns = findFnsWithScalar(t);
                pw.println("# scalar 0x" + Long.toHexString(t) + " ('" + tagStr(t) + "') appears in " + fns.size() + " fns:");
                for (Function f : fns) {
                    pw.println("#   " + f.getName() + " @ " + f.getEntryPoint());
                    targets.add(f);
                }
            }
            pw.println();
            for (Function f : targets) {
                pw.println("======================================================");
                pw.println("FUNCTION " + f.getName() + " @ " + f.getEntryPoint());
                pw.println("======================================================");
                DecompileResults r = d.decompileFunction(f, 120, new ConsoleTaskMonitor());
                if (r != null && r.decompileCompleted())
                    pw.println(r.getDecompiledFunction().getC());
                else
                    pw.println("(decompile failed)");
                pw.println();
            }
        }
        println("Wrote to " + OUT + " (" + targets.size() + " fns)");
    }

    String tagStr(long t) {
        byte[] b = {(byte)(t&0xff),(byte)((t>>8)&0xff),(byte)((t>>16)&0xff),(byte)((t>>24)&0xff)};
        return new String(b);
    }
}
