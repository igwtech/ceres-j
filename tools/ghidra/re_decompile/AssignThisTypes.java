// Assign each recovered /recovered struct as the `this` (param0) type on its class methods,
// so the decompiler renders this->field_0x.. in the GUI. Vtable-driven, same walk as StructRecover.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.*;
import java.util.regex.*;

public class AssignThisTypes extends GhidraScript {
    private static final Pattern NOISE = Pattern.compile(
        "^(std|stdext|Concurrency|__|_|operator|fuzzer|YAML|FID_)|<|>|\\.DLL|\\.dll", Pattern.CASE_INSENSITIVE);

    @Override public void run() throws Exception {
        DataTypeManager dtm = currentProgram.getDataTypeManager();
        CategoryPath cat = new CategoryPath("/recovered");
        SymbolTable st = currentProgram.getSymbolTable();
        Listing listing = currentProgram.getListing();
        var mem = currentProgram.getMemory();
        int ptrSize = currentProgram.getDefaultPointerSize();

        int classes=0, assigned=0, noStruct=0;
        SymbolIterator it = st.getSymbolIterator("vftable", true);
        while (it.hasNext()) {
            Symbol vsym = it.next();
            if (!vsym.getName().equals("vftable")) continue;
            Namespace ns = vsym.getParentNamespace();
            if (!(ns instanceof GhidraClass)) continue;
            if (NOISE.matcher(ns.getName()).find() || NOISE.matcher(ns.getName(true)).find()) continue;
            DataType s = dtm.getDataType(cat, ns.getName());
            if (!(s instanceof Structure)) { noStruct++; continue; }
            classes++;
            Pointer sp = dtm.getPointer(s);
            Address va = vsym.getAddress();
            for (int i = 0; i < 4096; i++) {
                Address slot = va.add((long) i * ptrSize);
                if (i > 0) { boolean b=false; for (Symbol x: st.getSymbols(slot)) if (x.getSource()!=SourceType.DEFAULT){b=true;break;} if(b) break; }
                long tgt; try { tgt = mem.getInt(slot) & 0xffffffffL; } catch (Exception e){ break; }
                Address ta; try { ta = va.getNewAddress(tgt); } catch (Exception e){ break; }
                MemoryBlock blk = mem.getBlock(ta);
                if (blk == null || !blk.isExecute()) break;
                Function f = listing.getFunctionAt(ta);
                if (f != null && f.isThunk()) { Function tf=f.getThunkedFunction(true); if(tf!=null) f=tf; }
                if (f == null || f.getParameterCount() == 0) continue;
                Parameter p0 = f.getParameter(0);
                try { p0.setDataType(sp, SourceType.ANALYSIS); assigned++; } catch (Exception ig) {}
            }
        }
        println("=== AssignThisTypes ===");
        println("classes with struct : " + classes);
        println("classes w/o struct  : " + noStruct);
        println("this-params typed    : " + assigned);
    }
}
