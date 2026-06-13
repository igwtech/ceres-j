// Full-recovery re-analysis: enable the high-value naming analyzers and re-run over the whole program.
// Persists into the shared .rep. Headless:
//   analyzeHeadless . Neocron2clien -process neocronclient.exe -noanalysis \
//     -scriptPath ceres-j/tools/ghidra -postScript RunFullAnalysis.java
//
// We use -noanalysis on the command line and drive analysis ourselves here so the
// analyzer set is explicit and reproducible, and so reAnalyzeAll re-runs even though
// the program was already analyzed once.
import ghidra.app.script.GhidraScript;
import ghidra.app.services.AnalysisPriority;
import ghidra.app.plugin.core.analysis.AutoAnalysisManager;
import ghidra.framework.options.Options;
import ghidra.program.model.listing.Program;
import java.util.*;

public class RunFullAnalysis extends GhidraScript {

    // Substrings of analyzer option names we want ON for max name recovery.
    private static final String[] WANT_ON = {
        "RTTI",                       // Windows x86 PE RTTI Analyzer -> classes + vftables
        "Demangler Microsoft",        // mangled @@ symbols -> readable C++ signatures
        "Function ID",                // statically-linked MSVCRT/STL naming
        "Decompiler Parameter ID",    // recover params/locals/calling conv
        "Decompiler Switch",          // jump tables
        "Aggressive Instruction Finder",
        "Create Address Tables",
        "Shared Return Calls",
        "Reference",
        "Data Reference",
        "Embedded Media",
        "Windows x86 PE Exception Handling",
        "MS RTTI",
        "Microsoft Visual C++ Exceptions"
    };

    @Override public void run() throws Exception {
        Program program = currentProgram;
        Options opts = program.getOptions(Program.ANALYSIS_PROPERTIES);

        println("=== Analyzer options BEFORE ===");
        List<String> names = new ArrayList<>(opts.getOptionNames());
        Collections.sort(names);
        int enabledCount = 0;
        for (String n : names) {
            // analyzer enable flags are boolean options named exactly the analyzer name
            try {
                if (opts.getType(n) == ghidra.framework.options.OptionType.BOOLEAN_TYPE) {
                    boolean cur = opts.getBoolean(n, false);
                    boolean want = cur;
                    for (String w : WANT_ON) {
                        if (n.contains(w)) { want = true; break; }
                    }
                    if (want != cur) {
                        opts.setBoolean(n, true);
                        println("  ENABLE  " + n);
                    }
                    if (want) enabledCount++;
                }
            } catch (Exception e) { /* non-boolean option, ignore */ }
        }
        println("Enabled analyzer flags: " + enabledCount);

        println("=== Scheduling full re-analysis over entire memory ===");
        AutoAnalysisManager mgr = AutoAnalysisManager.getAnalysisManager(program);
        mgr.reAnalyzeAll(null);              // mark whole program dirty for all enabled analyzers
        mgr.startAnalysis(monitor);          // synchronous in headless
        mgr.waitForAnalysis(null, monitor);
        println("=== Analysis complete ===");
    }
}
