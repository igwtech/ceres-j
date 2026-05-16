// FindFileCheck.java — Ghidra headless script.
//
// Locate the in-client "File System Check" routine(s) by xref to the known
// failure strings, then decompile:
//   - every function that references one of the strings,
//   - every function those reference (one level of callees), to catch the
//     hash.ini loader / per-file hasher,
// and dump raw disassembly of the failure-dialog region so the human can
// pick a minimal patch point. Also resolves the VA->file-offset for each
// candidate failure-branch instruction via FileBytes (8,977,408 B on-disk
// build mapping).
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless . Neocron2clien \
//       -process -noanalysis \
//       -scriptPath ceres-j/tools/ghidra -postScript FindFileCheck.java

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.database.mem.FileBytes;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.mem.MemoryBlockSourceInfo;
import ghidra.program.model.symbol.RefType;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

public class FindFileCheck extends GhidraScript {

    private static final String[] NEEDLES = new String[] {
        "ini\\hash.ini",
        "hash.ini",
        "File System Check failed",
        "File System Check",
        "file(s) corrupt or missing",
        "could not load file table",
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/_filecheck_raw.txt";

    private Listing listing;
    private ReferenceManager refMgr;
    private DecompInterface decomp;

    @Override
    protected void run() throws Exception {
        decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        listing = currentProgram.getListing();
        refMgr = currentProgram.getReferenceManager();

        // 1. find matching strings
        TreeMap<Address, String> strHits = new TreeMap<>();
        DataIterator dit = listing.getDefinedData(true);
        while (dit.hasNext()) {
            Data d = dit.next();
            if (d.getDataType() == null) continue;
            String dt = d.getDataType().getName().toLowerCase();
            if (!dt.contains("string") && !dt.contains("char")) continue;
            Object v = d.getValue();
            if (!(v instanceof String)) continue;
            String s = (String) v;
            for (String n : NEEDLES) {
                if (s.contains(n)) {
                    strHits.put(d.getAddress(), s);
                    println("STR @ " + d.getAddress() + "  val=" + trunc(s));
                    break;
                }
            }
        }
        println("matched " + strHits.size() + " strings");

        // 2. functions that reference them (level-0), and callees (level-1)
        Set<Address> level0 = new LinkedHashSet<>();
        TreeMap<Address, StringBuilder> why = new TreeMap<>();
        for (var e : strHits.entrySet()) {
            Address sa = e.getKey();
            ReferenceIterator refs = refMgr.getReferencesTo(sa);
            while (refs.hasNext()) {
                Reference r = refs.next();
                Address from = r.getFromAddress();
                Function fn = listing.getFunctionContaining(from);
                if (fn == null) continue;
                Address ep = fn.getEntryPoint();
                level0.add(ep);
                why.computeIfAbsent(ep, k -> new StringBuilder())
                   .append("  ref@").append(from).append(" -> str@")
                   .append(sa).append("  \"").append(trunc(e.getValue()))
                   .append("\"\n");
                println("  ref @ " + from + " in fn " + fn.getName()
                        + " @ " + ep);
            }
        }

        // collect level-1 callees of every level-0 function
        Set<Address> level1 = new LinkedHashSet<>();
        for (Address ep : level0) {
            Function fn = listing.getFunctionAt(ep);
            if (fn == null) continue;
            for (Function c : fn.getCalledFunctions(new ConsoleTaskMonitor())) {
                Function real = c.isThunk() ? c.getThunkedFunction(true) : c;
                if (real != null) level1.add(real.getEntryPoint());
            }
        }

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# File System Check discovery — raw");
            pw.println("# matched " + strHits.size() + " strings, "
                    + level0.size() + " level-0 fns, "
                    + level1.size() + " level-1 callees");
            pw.println();

            pw.println("== MATCHED STRINGS ==");
            for (var e : strHits.entrySet()) {
                pw.println("  " + e.getKey() + "  \"" + trunc(e.getValue()) + "\"");
                // VA->file off for the string itself (handy for re-hash route)
                pw.println("     " + fileOffInfo(e.getKey()));
            }
            pw.println();

            pw.println("== LEVEL-0 FUNCTIONS (reference a failure string) ==");
            for (Address ep : level0) {
                Function fn = listing.getFunctionAt(ep);
                if (fn == null) continue;
                pw.println("======================================================");
                pw.println("L0 FUNCTION " + fn.getName() + " @ " + ep);
                pw.println("======================================================");
                StringBuilder w = why.get(ep);
                if (w != null) { pw.println("Why:"); pw.print(w); }
                pw.println();
                pw.println("--- DECOMP ---");
                pw.println(decompile(fn));
                pw.println();
                pw.println("--- DISASM (with VA->fileoff for branch/cmp ops) ---");
                dumpDisasm(pw, fn);
                pw.println();
            }

            pw.println("== LEVEL-1 CALLEES (hash.ini loader / hasher candidates) ==");
            for (Address ep : level1) {
                if (level0.contains(ep)) continue;
                Function fn = listing.getFunctionAt(ep);
                if (fn == null) continue;
                pw.println("------------------------------------------------------");
                pw.println("L1 CALLEE " + fn.getName() + " @ " + ep);
                pw.println("------------------------------------------------------");
                pw.println(decompile(fn));
                pw.println();
            }
        }
        println("Wrote " + OUT_PATH);
    }

    private String decompile(Function fn) {
        try {
            DecompileResults res = decomp.decompileFunction(
                    fn, 120, new ConsoleTaskMonitor());
            if (res != null && res.decompileCompleted())
                return res.getDecompiledFunction().getC();
        } catch (Exception ex) {
            return "(decompile exception: " + ex + ")";
        }
        return "(decompile failed)";
    }

    private void dumpDisasm(PrintWriter pw, Function fn) {
        InstructionIterator it = listing.getInstructions(fn.getBody(), true);
        while (it.hasNext()) {
            Instruction ins = it.next();
            String mn = ins.getMnemonicString().toUpperCase();
            String line = ins.getAddress() + ":  " + ins;
            // annotate conditional jumps / cmp / test / call with file offset
            if (mn.startsWith("J") || mn.equals("CMP") || mn.equals("TEST")
                    || mn.equals("CALL") || mn.equals("MOV")
                    || mn.startsWith("SET")) {
                String fo = fileOffShort(ins.getAddress());
                line += "    ; " + bytesOf(ins) + (fo != null ? "  fileoff=" + fo : "");
            }
            pw.println(line);
        }
    }

    private String bytesOf(Instruction ins) {
        try {
            byte[] b = ins.getBytes();
            StringBuilder sb = new StringBuilder("[");
            for (byte x : b) sb.append(String.format("%02x ", x & 0xff));
            return sb.toString().trim() + "]";
        } catch (Exception e) { return "[?]"; }
    }

    private String fileOffShort(Address va) {
        try {
            MemoryBlock blk = currentProgram.getMemory().getBlock(va);
            if (blk == null) return null;
            for (MemoryBlockSourceInfo si : blk.getSourceInfos()) {
                if (va.compareTo(si.getMinAddress()) >= 0
                        && va.compareTo(si.getMaxAddress()) <= 0
                        && si.getFileBytesOffset() >= 0) {
                    long delta = va.subtract(si.getMinAddress());
                    long fo = si.getFileBytesOffset() + delta;
                    return "0x" + Long.toHexString(fo);
                }
            }
        } catch (Exception e) { }
        return null;
    }

    private String fileOffInfo(Address va) {
        try {
            MemoryBlock blk = currentProgram.getMemory().getBlock(va);
            if (blk == null) return "(no block)";
            for (MemoryBlockSourceInfo si : blk.getSourceInfos()) {
                if (va.compareTo(si.getMinAddress()) >= 0
                        && va.compareTo(si.getMaxAddress()) <= 0
                        && si.getFileBytesOffset() >= 0) {
                    long delta = va.subtract(si.getMinAddress());
                    long fo = si.getFileBytesOffset() + delta;
                    var fbOpt = si.getFileBytes();
                    StringBuilder sb = new StringBuilder();
                    sb.append("fileoff=0x").append(Long.toHexString(fo));
                    if (fbOpt != null && fbOpt.isPresent()) {
                        FileBytes fb = fbOpt.get();
                        sb.append(" sig=[");
                        for (int i = 0; i < 16; i++)
                            sb.append(String.format("%02x ",
                                fb.getOriginalByte(fo + i) & 0xff));
                        sb.append("]");
                    }
                    return sb.toString().trim();
                }
            }
        } catch (Exception e) { return "(err " + e + ")"; }
        return "(unmapped)";
    }

    private String trunc(String s) {
        s = s.replace("\n", "\\n").replace("\r", "\\r");
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
