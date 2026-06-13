// Tag functions with the nclib source file they belong to, derived from __FILE__/assert
// string literals (e.g. "...\src\nclib\genesis\Actor\actor.cpp") that the function references.
// Adds a plate comment "[src: <relpath>]" to each referencing function. Non-destructive: never
// renames or overwrites existing names. Headless postScript.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.StringDataInstance;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import java.util.*;
import java.util.regex.*;

public class TagBySourceFile extends GhidraScript {

    // capture from "nclib\..." or "src\..." onward, normalize slashes
    private static final Pattern SRC = Pattern.compile(
        "(?:src[\\\\/])?((?:nclib|genesis|gamebase|net|base|RPOS|common)[\\\\/][^\"\\n]*?\\.(?:cpp|c|cxx|h|hpp))",
        Pattern.CASE_INSENSITIVE);
    // fallback: any *.cpp basename
    private static final Pattern ANYCPP = Pattern.compile("([A-Za-z0-9_]+\\.(?:cpp|cxx))");

    @Override public void run() throws Exception {
        Listing listing = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();

        Map<Function, TreeSet<String>> fnFiles = new HashMap<>();
        Map<String, Integer> fileFnCount = new TreeMap<>();
        int strData = 0, srcStrings = 0, taggedRefs = 0;

        DataIterator dit = listing.getDefinedData(true);
        while (dit.hasNext() && !monitor.isCancelled()) {
            Data d = dit.next();
            if (d == null) continue;
            Object v = d.getValue();
            if (!(v instanceof String)) continue;
            strData++;
            String s = (String) v;
            String rel = null;
            Matcher m = SRC.matcher(s);
            if (m.find()) rel = m.group(1).replace('\\', '/');
            else {
                Matcher m2 = ANYCPP.matcher(s);
                if (m2.find() && s.length() < 120) rel = m2.group(1);
            }
            if (rel == null) continue;
            srcStrings++;

            ReferenceIterator refs = rm.getReferencesTo(d.getAddress());
            while (refs.hasNext()) {
                Reference r = refs.next();
                Function f = listing.getFunctionContaining(r.getFromAddress());
                if (f == null) continue;
                fnFiles.computeIfAbsent(f, k -> new TreeSet<>()).add(rel);
            }
        }

        for (Map.Entry<Function, TreeSet<String>> e : fnFiles.entrySet()) {
            Function f = e.getKey();
            String tag = "[src: " + String.join(", ", e.getValue()) + "]";
            String existing = f.getComment();
            if (existing == null || !existing.contains("[src:")) {
                f.setComment((existing == null ? "" : existing + "\n") + tag);
                taggedRefs++;
            }
            for (String file : e.getValue())
                fileFnCount.merge(file, 1, Integer::sum);
        }

        println("=== TagBySourceFile ===");
        println("string Data scanned     : " + strData);
        println("source-path strings     : " + srcStrings);
        println("functions tagged        : " + taggedRefs);
        println("distinct source files   : " + fileFnCount.size());
        println("---- functions per source file (top 40) ----");
        fileFnCount.entrySet().stream()
            .sorted((a,b)->b.getValue()-a.getValue())
            .limit(40)
            .forEach(en -> println(String.format("  %4d  %s", en.getValue(), en.getKey())));
    }
}
