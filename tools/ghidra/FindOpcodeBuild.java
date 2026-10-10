// FindOpcodeBuild.java — find code that builds a message starting with a
// given opcode byte: a "MOV byte ptr [base + d], OP" followed within N
// instructions by a store to [base + d + 1]. Prints the window.
// Args: <out-file> <opcode hex> [window=10]
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import java.io.PrintWriter;
import java.util.regex.*;

public class FindOpcodeBuild extends GhidraScript {
    static final Pattern MEM = Pattern.compile("\\[(\\w+)(?: \\+ (0x[0-9a-f]+))?\\]");
    @Override protected void run() throws Exception {
        String[] a = getScriptArgs();
        long op = Long.decode(a[1]);
        int win = a.length > 2 ? Integer.parseInt(a[2]) : 10;
        try (PrintWriter pw = new PrintWriter(a[0])) {
            InstructionIterator it = currentProgram.getListing().getInstructions(true);
            while (it.hasNext()) {
                Instruction ins = it.next();
                if (!ins.getMnemonicString().equals("MOV") || ins.getNumOperands() != 2) continue;
                String d = ins.getDefaultOperandRepresentation(0).toLowerCase();
                if (!d.startsWith("byte ptr")) continue;
                Scalar s = ins.getScalar(1);
                if (s == null || s.getUnsignedValue() != op) continue;
                Matcher m = MEM.matcher(d);
                if (!m.find()) continue;
                String base = m.group(1);
                long off = m.group(2) == null ? 0 : Long.decode(m.group(2));
                String want = "[" + base + " + 0x" + Long.toHexString(off + 1) + "]";
                Instruction n = ins.getNext();
                StringBuilder sb = new StringBuilder();
                boolean hit = false;
                for (int k = 0; k < win && n != null; k++, n = n.getNext()) {
                    sb.append("    ").append(n.getAddress()).append("  ").append(n).append('\n');
                    if (n.getNumOperands() > 0 && n.getDefaultOperandRepresentation(0).toLowerCase().contains(want)) hit = true;
                }
                if (!hit) continue;
                Function fn = getFunctionContaining(ins.getAddress());
                pw.println(ins.getAddress() + "  " + ins + "   in " + (fn == null ? "?" : fn.getName()));
                pw.print(sb);
            }
        }
    }
}
