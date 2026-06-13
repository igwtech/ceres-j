// Export recovered structures (and all non-trivial program structs) to a C header.
// Writes ceres-j/docs/re_export/structs.h and structs_recovered.h (just the /recovered category).
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.*;
import java.io.*;
import java.util.*;

public class StructExport extends GhidraScript {
    @Override public void run() throws Exception {
        File outDir = new File("ceres-j/docs/re_export");
        outDir.mkdirs();
        DataTypeManager dtm = currentProgram.getDataTypeManager();

        int recovered = exportCategory(dtm, "/recovered",
            new File(outDir, "structs_recovered.h"));
        int all = exportAll(dtm, new File(outDir, "structs_all.h"));

        println("=== StructExport ===");
        println("recovered-category structs : " + recovered + " -> structs_recovered.h");
        println("all program structs/unions : " + all + " -> structs_all.h");
    }

    private int exportCategory(DataTypeManager dtm, String path, File out) throws IOException {
        Category c = dtm.getCategory(new CategoryPath(path));
        int n = 0;
        try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
            pw.println("// Recovered class layouts from neocronclient.exe (offset-level fields).");
            pw.println("// Field names are offset-based; types are decompiler-inferred.\n");
            if (c != null) {
                DataType[] dts = c.getDataTypes();
                Arrays.sort(dts, Comparator.comparing(DataType::getName));
                for (DataType dt : dts) if (dt instanceof Composite) { pw.println(toC((Composite) dt)); n++; }
            }
        }
        return n;
    }

    private int exportAll(DataTypeManager dtm, File out) throws IOException {
        int n = 0;
        try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
            pw.println("// All structs/unions in the program datatype manager.\n");
            Iterator<DataType> it = dtm.getAllDataTypes();
            List<Composite> comps = new ArrayList<>();
            while (it.hasNext()) { DataType dt = it.next(); if (dt instanceof Composite) comps.add((Composite) dt); }
            comps.sort(Comparator.comparing(DataType::getName));
            for (Composite c : comps) { pw.println(toC(c)); n++; }
        }
        return n;
    }

    private String toC(Composite c) {
        StringBuilder sb = new StringBuilder();
        String kind = (c instanceof Union) ? "union" : "struct";
        sb.append(kind).append(" ").append(c.getName())
          .append(" {  // size 0x").append(Integer.toHexString(c.getLength())).append("\n");
        for (DataTypeComponent comp : c.getDefinedComponents()) {
            String fn = comp.getFieldName();
            if (fn == null) fn = "field_0x" + Integer.toHexString(comp.getOffset());
            sb.append(String.format("    /* 0x%04x */ %s %s;%n",
                comp.getOffset(), comp.getDataType().getName(), fn));
        }
        sb.append("};\n");
        return sb.toString();
    }
}
