// Fast export of recovered structure: a function table CSV, a class/vtable listing,
// and a namespace summary. Written under ceres-j/docs/re_export/.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.data.*;
import java.io.*;
import java.util.*;

public class ExportNames extends GhidraScript {
    @Override public void run() throws Exception {
        File outDir = new File("ceres-j/docs/re_export");
        outDir.mkdirs();
        Listing listing = currentProgram.getListing();
        SymbolTable stab = currentProgram.getSymbolTable();

        // 1) functions.csv : addr,name,namespace,sig,src_comment
        int total=0, named=0, fun=0;
        try (PrintWriter pw = new PrintWriter(new FileWriter(new File(outDir,"functions.csv")))) {
            pw.println("address,name,namespace,signature,src");
            FunctionIterator it = listing.getFunctions(true);
            while (it.hasNext()) {
                Function f = it.next();
                total++;
                String n = f.getName();
                if (n.startsWith("FUN_")) fun++; else named++;
                String ns = f.getParentNamespace()==null?"":f.getParentNamespace().getName(true);
                String sig = f.getPrototypeString(false,false).replace("\"","'").replace("\n"," ");
                String cmt = f.getComment()==null?"":f.getComment().replace("\n"," ").replace("\"","'");
                pw.printf("%s,\"%s\",\"%s\",\"%s\",\"%s\"%n",
                    f.getEntryPoint(), n.replace("\"","'"), ns, sig, cmt);
            }
        }

        // 2) classes.txt : GhidraClass namespaces + their function members
        int classes=0;
        try (PrintWriter pw = new PrintWriter(new FileWriter(new File(outDir,"classes.txt")))) {
            Iterator<GhidraClass> cit = stab.getClassNamespaces();
            List<GhidraClass> list = new ArrayList<>();
            while (cit.hasNext()) list.add(cit.next());
            list.sort(Comparator.comparing(g->g.getName(true)));
            for (GhidraClass g : list) {
                classes++;
                pw.println("class " + g.getName(true));
                SymbolIterator si = stab.getSymbols(g);
                while (si.hasNext()) {
                    Symbol s = si.next();
                    pw.println("    " + s.getSymbolType() + "  " + s.getName() + "  @" + s.getAddress());
                }
            }
        }

        // 3) vftables.txt
        int vft=0;
        try (PrintWriter pw = new PrintWriter(new FileWriter(new File(outDir,"vftables.txt")))) {
            SymbolIterator si = stab.getAllSymbols(false);
            while (si.hasNext()) {
                Symbol s = si.next();
                if (s.getName().contains("vftable") || s.getName().contains("vbtable")) {
                    vft++;
                    pw.println(s.getAddress()+"  "+s.getName(true));
                }
            }
        }

        // 4) datatypes (recovered classes/structs)
        DataTypeManager dtm = currentProgram.getDataTypeManager();
        int dtc = dtm.getDataTypeCount(false);

        println("=== ExportNames written to " + outDir.getAbsolutePath() + " ===");
        println("functions total="+total+" named="+named+" FUN_="+fun);
        println("classes="+classes+" vftables="+vft+" datatypes="+dtc);
    }
}
