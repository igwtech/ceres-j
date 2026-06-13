// Bind virtual methods to their class: for every RTTI 'vftable' symbol, walk its function-pointer
// array and, for each slot whose target is an unnamed Global FUN_ function, reparent it under the
// owning class namespace and name it <Class>::vfunc_<index>. Non-destructive: only touches FUN_,
// Global-scoped targets (never overrides demangled/user names or functions already in a class).
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.*;

public class BindVtableMethods extends GhidraScript {
    @Override public void run() throws Exception {
        SymbolTable st = currentProgram.getSymbolTable();
        Listing listing = currentProgram.getListing();
        var mem = currentProgram.getMemory();
        int ptr = currentProgram.getDefaultPointerSize();

        int vtables=0, slots=0, bound=0, skippedNamed=0, skippedClassed=0;
        SymbolIterator it = st.getSymbolIterator("vftable", true);  // primary symbols named exactly vftable
        while (it.hasNext() && !monitor.isCancelled()) {
            Symbol vsym = it.next();
            if (!vsym.getName().equals("vftable")) continue;
            Namespace cls = vsym.getParentNamespace();
            if (cls == null || cls.isGlobal()) continue;   // need an owning class
            vtables++;
            Address a = vsym.getAddress();
            int idx = 0;
            while (true) {
                Address slot = a.add((long) idx * ptr);
                // stop if a new symbol begins here (next vtable / data) after slot 0
                if (idx > 0) {
                    Symbol[] at = st.getSymbols(slot);
                    boolean boundary = false;
                    for (Symbol s : at) if (s.getSource()!=SourceType.DEFAULT) { boundary=true; break; }
                    if (boundary) break;
                }
                long target;
                try { target = mem.getInt(slot) & 0xffffffffL; }
                catch (Exception e) { break; }
                Address tgt;
                try { tgt = a.getNewAddress(target); } catch (Exception e) { break; }
                MemoryBlock blk = mem.getBlock(tgt);
                if (blk == null || !blk.isExecute()) break;          // not code -> end of vtable
                Function f = listing.getFunctionAt(tgt);
                if (f == null) {
                    f = createFunction(tgt, null);                    // disasm/create if missing
                    if (f == null) break;
                }
                slots++;
                if (!f.getParentNamespace().isGlobal()) { skippedClassed++; idx++; continue; }
                if (!f.getName().startsWith("FUN_") && !f.getName().startsWith("vfunc_")) {
                    skippedNamed++; idx++; continue;                  // keep demangled/user names
                }
                try {
                    f.setParentNamespace(cls);
                    f.setName("vfunc_" + idx, SourceType.ANALYSIS);
                    bound++;
                } catch (Exception e) { /* duplicate / conflict, skip */ }
                idx++;
                if (idx > 4096) break;                                // sanity cap
            }
        }
        println("=== BindVtableMethods ===");
        println("vftables processed : " + vtables);
        println("slots walked       : " + slots);
        println("methods bound      : " + bound);
        println("skipped (already named)   : " + skippedNamed);
        println("skipped (already in class): " + skippedClassed);
    }
}
