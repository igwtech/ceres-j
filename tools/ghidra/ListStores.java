// ListStores.java — list every MOV store to [reg + OFFSET] with the given
// operand-size prefix, plus the containing function and its callers.
// Args: <out-file> <size-prefix e.g. "dword", "" = any> <offset hex>
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.Reference;
import java.io.PrintWriter;

public class ListStores extends GhidraScript {
    @Override protected void run() throws Exception {
        String[] a = getScriptArgs();
        String suffix = " + " + a[2].toLowerCase() + "]";
        try (PrintWriter pw = new PrintWriter(a[0])) {
            InstructionIterator it = currentProgram.getListing().getInstructions(true);
            while (it.hasNext()) {
                Instruction ins = it.next();
                if (ins.getNumOperands() < 2) continue;
                String mn = ins.getMnemonicString();
                if (!(mn.startsWith("MOV") || mn.startsWith("VMOV"))) continue;
                String dst = ins.getDefaultOperandRepresentation(0).toLowerCase();
                if (!dst.startsWith(a[1].toLowerCase()) || !dst.endsWith(suffix)) continue;
                Function fn = currentProgram.getListing().getFunctionContaining(ins.getAddress());
                StringBuilder cs = new StringBuilder();
                if (fn != null) for (Reference r : getReferencesTo(fn.getEntryPoint())) {
                    Function c = currentProgram.getListing().getFunctionContaining(r.getFromAddress());
                    if (c != null) cs.append(' ').append(c.getName());
                }
                pw.println(ins.getAddress() + "  " + ins + "  in " + (fn == null ? "?" : fn.getName()
                        + " (size " + fn.getBody().getNumAddresses() + ") callers:" + cs));
            }
        }
    }
}
