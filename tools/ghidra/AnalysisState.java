// Reports current analysis/naming state of the program so we know what's left to do.
// Headless:  analyzeHeadless <projDir> <projName> -process <prog> -noanalysis -scriptPath <dir> -postScript AnalysisState.java
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.address.*;
import ghidra.program.model.data.*;
import java.util.*;

public class AnalysisState extends GhidraScript {
    @Override public void run() throws Exception {
        Listing listing = currentProgram.getListing();
        SymbolTable st = currentProgram.getSymbolTable();

        int total = 0, funUnnamed = 0, named = 0, thunks = 0, demangled = 0, libNamed = 0;
        FunctionIterator fit = listing.getFunctions(true);
        while (fit.hasNext()) {
            Function f = fit.next();
            total++;
            String n = f.getName();
            if (f.isThunk()) thunks++;
            if (n.startsWith("FUN_")) funUnnamed++;
            else {
                named++;
                if (n.contains("::") || n.contains("@") || n.indexOf('~') >= 0) demangled++;
            }
            SourceType src = f.getSymbol() != null ? f.getSymbol().getSource() : null;
            if (src == SourceType.IMPORTED || src == SourceType.ANALYSIS) {
                if (!n.startsWith("FUN_")) libNamed++;
            }
        }

        // Namespaces / classes
        int classCount = 0;
        Set<String> nsNames = new TreeSet<>();
        for (GhidraClass gc : new java.util.ArrayList<GhidraClass>()) {}
        SymbolIterator nsIt = st.getDefinedSymbols();
        // count GhidraClass namespaces
        Iterator<GhidraClass> cls = currentProgram.getSymbolTable().getClassNamespaces();
        while (cls.hasNext()) { GhidraClass g = cls.next(); classCount++; if (nsNames.size()<40) nsNames.add(g.getName(true)); }

        // vftables (RTTI output) - symbols named *vftable* or *_vbtable*
        int vftables = 0;
        SymbolIterator allSyms = st.getAllSymbols(false);
        int labelSyms = 0;
        while (allSyms.hasNext()) {
            Symbol s = allSyms.next();
            String sn = s.getName();
            if (sn.contains("vftable") || sn.contains("vbtable")) vftables++;
        }

        // Data types (recovered structs/classes)
        DataTypeManager dtm = currentProgram.getDataTypeManager();
        int dtCount = dtm.getDataTypeCount(false);

        println("================ ANALYSIS STATE ================");
        println("Image base        : " + currentProgram.getImageBase());
        println("Functions total   : " + total);
        println("  FUN_ unnamed    : " + funUnnamed);
        println("  named           : " + named);
        println("  with C++ sig (::/@/~): " + demangled);
        println("  thunks          : " + thunks);
        println("C++ classes (GhidraClass namespaces): " + classCount);
        println("vftable symbols   : " + vftables);
        println("DataTypes in DTM  : " + dtCount);
        println("---- sample class namespaces ----");
        for (String s : nsNames) println("  " + s);
        println("================================================");
    }
}
