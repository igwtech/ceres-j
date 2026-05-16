import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import java.util.*;

public class FileCheckCallers extends GhidraScript {
    @Override protected void run() throws Exception {
        // Ghidra-build VAs of the two file-check functions
        long[] targets = { 0x004609e0L, 0x0043f7d0L };
        for (long t : targets) {
            Address ep = toAddr(t);
            Function fn = getFunctionAt(ep);
            println("=== TARGET " + (fn!=null?fn.getName():"?") + " @ " + ep + " ===");
            ReferenceIterator it = currentProgram.getReferenceManager().getReferencesTo(ep);
            int n=0;
            while (it.hasNext()) {
                Reference r = it.next();
                Address from = r.getFromAddress();
                Function cf = getFunctionContaining(from);
                println("  CALLED-FROM " + from + "  inFn=" +
                    (cf!=null?cf.getName()+"@"+cf.getEntryPoint():"?") +
                    "  type=" + r.getReferenceType());
                n++;
            }
            println("  total xrefs: " + n);
            // also list thunks to it
            for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
                if (f.isThunk()) {
                    Function tk = f.getThunkedFunction(true);
                    if (tk!=null && tk.getEntryPoint().equals(ep))
                        println("  THUNK " + f.getName() + " @ " + f.getEntryPoint());
                }
            }
        }
        // Walk callers of FUN_004609e0 up 2 levels to find the boot path
        println("=== caller-of-caller for FUN_004609e0 ===");
        Set<Address> seen = new HashSet<>();
        Deque<long[]> q = new ArrayDeque<>();
        q.add(new long[]{0x004609e0L,0});
        while(!q.isEmpty()){
            long[] cur=q.poll();
            Address ep=toAddr(cur[0]);
            if(!seen.add(ep)||cur[1]>2) continue;
            Function fn=getFunctionContaining(ep);
            ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(
                fn!=null?fn.getEntryPoint():ep);
            while(it.hasNext()){
                Reference r=it.next();
                Function cf=getFunctionContaining(r.getFromAddress());
                if(cf!=null){
                    println("  L"+cur[1]+" "+(fn!=null?fn.getName():"?")+
                        " <- "+cf.getName()+"@"+cf.getEntryPoint()+
                        " (call@"+r.getFromAddress()+")");
                    q.add(new long[]{cf.getEntryPoint().getOffset(),cur[1]+1});
                }
            }
        }
    }
}
