// Full decompilation export. Decompiles every function and writes C grouped by namespace
// (one .c per top-level class/namespace, plus _global.c for the rest) under
// ceres-j/docs/re_export/decomp/. Heavy: expect a long run on a 9MB exe.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.Namespace;
import java.io.*;
import java.util.*;

public class ExportDecomp extends GhidraScript {
    @Override public void run() throws Exception {
        File outDir = new File("ceres-j/docs/re_export/decomp");
        outDir.mkdirs();

        DecompInterface dec = new DecompInterface();
        DecompileOptions o = new DecompileOptions();
        dec.setOptions(o);
        dec.toggleCCode(true);
        dec.toggleSyntaxTree(false);
        dec.setSimplificationStyle("decompile");
        if (!dec.openProgram(currentProgram)) {
            println("FATAL: decompiler failed to open: " + dec.getLastMessage());
            return;
        }

        Map<String, PrintWriter> writers = new HashMap<>();
        Listing listing = currentProgram.getListing();
        int total=0, ok=0, fail=0;
        FunctionIterator it = listing.getFunctions(true);
        long t0 = 0;
        while (it.hasNext() && !monitor.isCancelled()) {
            Function f = it.next();
            total++;
            Namespace ns = f.getParentNamespace();
            String key = (ns==null||ns.isGlobal())? "_global" : ns.getName(false);
            key = key.replaceAll("[^A-Za-z0-9_]+","_");
            if (key.length()>60) key = key.substring(0,60);
            PrintWriter pw = writers.get(key);
            if (pw==null) {
                pw = new PrintWriter(new BufferedWriter(new FileWriter(new File(outDir, key+".c"))));
                writers.put(key, pw);
            }
            DecompileResults res = dec.decompileFunction(f, 60, monitor);
            if (res!=null && res.decompileCompleted() && res.getDecompiledFunction()!=null) {
                pw.println("// " + f.getEntryPoint() + "  " + f.getName(true));
                pw.println(res.getDecompiledFunction().getC());
                pw.println();
                ok++;
            } else {
                pw.println("// " + f.getEntryPoint() + "  " + f.getName(true) + "  -- DECOMPILE FAILED: "
                    + (res==null?"null":res.getErrorMessage()));
                pw.println();
                fail++;
            }
            if (total % 500 == 0) println("  decompiled " + total + " (ok="+ok+" fail="+fail+")");
        }
        for (PrintWriter pw : writers.values()) pw.close();
        dec.dispose();
        println("=== ExportDecomp done: total="+total+" ok="+ok+" fail="+fail+" files="+writers.size()+" -> "+outDir.getAbsolutePath());
    }
}
