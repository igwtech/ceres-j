// FindLogLevelInit.java — Ghidra headless script.
//
// DAT_00b05078 is the single log-verbosity gate, read/written ONLY inside
// the sink FUN_004471c0 (write at 0044720b = `atoi(param_1+2)` on an "@L<n>"
// log message). So verbosity is set in-band by emitting a log line of the
// form "@L<number>". This script finds:
//   1. The default/initial value of DAT_00b05078 (is it a .data/.bss init?).
//   2. Every defined string beginning with "@L" and its referencing fn
//      (these are the call sites that set the level).
//   3. Format strings like "@L%d" / "@L%i" and their callers.
//   4. The log file name string near DAT_00a0b73c (sink's ofstream target).
//   5. argv / GetCommandLine / config-read functions that build an "@L" arg.
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless . Neocron2clien \
//       -process -noanalysis \
//       -scriptPath ceres-j/tools/ghidra -postScript FindLogLevelInit.java

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;

public class FindLogLevelInit extends GhidraScript {

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/_loglevelinit_raw.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Log level init / @L caller discovery");
            pw.println();

            // 1. Default value of DAT_00b05078.
            Address lv = currentProgram.getAddressFactory().getAddress("00b05078");
            try {
                int v = currentProgram.getMemory().getInt(lv);
                pw.println("DAT_00b05078 raw initial bytes (LE int) = " + v
                        + "  (0x" + Integer.toHexString(v) + ")");
            } catch (MemoryAccessException me) {
                pw.println("DAT_00b05078 in .bss (zero-initialized) -> default = 0");
            }
            // Dump nearby bytes for context.
            pw.print("bytes @00b05070..00b05088:");
            for (int i = 0; i < 0x18; i++) {
                try {
                    byte b = currentProgram.getMemory().getByte(lv.add(i - 8));
                    pw.print(String.format(" %02x", b & 0xff));
                } catch (MemoryAccessException me) {
                    pw.print(" ??");
                }
            }
            pw.println();
            pw.println("(if all ?? => .bss => default level = 0 => only FATAL @?F logged)");
            pw.println();

            // 2 & 3. Strings beginning with "@L" or containing @L%.
            pw.println("== @L STRINGS (level-set call sites) ==");
            Set<Address> callers = new LinkedHashSet<>();
            DataIterator dit = listing.getDefinedData(true);
            while (dit.hasNext()) {
                Data d = dit.next();
                if (d.getDataType() == null) continue;
                String dt = d.getDataType().getName().toLowerCase();
                if (!dt.contains("string") && !dt.contains("char")) continue;
                Object v = d.getValue();
                if (!(v instanceof String)) continue;
                String s = (String) v;
                boolean atL = s.startsWith("@L") || s.contains("@L%")
                        || s.startsWith("@E") || s.startsWith("@F")
                        || s.contains("@?F") || s.startsWith("@P")
                        || s.contains("@?P");
                if (!atL) continue;
                String disp = s.replace("\n", "\\n").replace("\r", "\\r");
                if (disp.length() > 80) disp = disp.substring(0, 80) + "...";
                pw.println("STR @ " + d.getAddress() + "  \"" + disp + "\"");
                ReferenceIterator refs = refMgr.getReferencesTo(d.getAddress());
                while (refs.hasNext()) {
                    Reference r = refs.next();
                    Function fn = listing.getFunctionContaining(r.getFromAddress());
                    pw.println("    ref " + r.getFromAddress()
                            + (fn != null ? " in " + fn.getName()
                                    + " @ " + fn.getEntryPoint() : " (orphan)"));
                    if (fn != null) callers.add(fn.getEntryPoint());
                }
            }
            pw.println();

            // 4. Log file name: DAT_00b050b0 set to &DAT_00a0b73c; print string
            //    there and around.
            pw.println("== LOG FILE NAME REGION (near 00a0b73c) ==");
            for (int off = -0x40; off < 0x80; off += 4) {
                Address a;
                try { a = currentProgram.getAddressFactory()
                        .getAddress("00a0b73c").add(off); }
                catch (Exception e) { continue; }
                Data dd = listing.getDefinedDataAt(a);
                if (dd != null && dd.getValue() instanceof String) {
                    pw.println("  " + a + "  \""
                            + ((String) dd.getValue())
                                .replace("\n", "\\n") + "\"");
                }
            }
            // Raw read at 00a0b73c.
            try {
                StringBuilder sb = new StringBuilder();
                Address base = currentProgram.getAddressFactory()
                        .getAddress("00a0b73c");
                for (int i = 0; i < 64; i++) {
                    byte b = currentProgram.getMemory().getByte(base.add(i));
                    if (b == 0) break;
                    sb.append((char) (b & 0xff));
                }
                pw.println("  raw@00a0b73c = \"" + sb + "\"");
            } catch (Exception e) {
                pw.println("  raw@00a0b73c read failed: " + e);
            }
            pw.println();

            // 5. Decompile callers of @L strings + the thunk_FUN_00447e40
            //    (the printf wrapper) callers near command-line parse.
            pw.println("== @L CALLER DECOMPILATIONS ==");
            for (Address ep : callers) {
                Function fn = listing.getFunctionAt(ep);
                if (fn == null) continue;
                pw.println("--------------------------------------------------");
                pw.println("FN " + fn.getName() + " @ " + ep);
                pw.println("--------------------------------------------------");
                DecompileResults res = decomp.decompileFunction(
                        fn, 90, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted())
                    pw.println(res.getDecompiledFunction().getC());
                else
                    pw.println("(decompile failed)");
                pw.println();
            }

            // Also: who calls the sink FUN_004471c0 with a constant @L / @E
            // literal that we may have missed; list direct refs to the sink.
            pw.println("== DIRECT REFS TO SINK FUN_004471c0 (count) ==");
            Address sink = currentProgram.getAddressFactory()
                    .getAddress("004471c0");
            ReferenceIterator sr = refMgr.getReferencesTo(sink);
            int c = 0;
            while (sr.hasNext()) { sr.next(); c++; }
            pw.println("  direct refs = " + c
                    + " (most go through a thunk; sink is called widely)");
        }
        println("Wrote " + OUT_PATH);
    }
}
