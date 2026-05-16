import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class FileCheckThunkCallers extends GhidraScript {
    DecompInterface di;
    @Override protected void run() throws Exception {
        di=new DecompInterface(); di.setOptions(new DecompileOptions());
        di.openProgram(currentProgram);
        long[] thunks={0x0040150fL,0x00414f01L};
        String[] nm={"thunk_FUN_004609e0(in-client check)","thunk_FUN_0043f7d0(updater)"};
        for(int k=0;k<thunks.length;k++){
            Address ep=toAddr(thunks[k]);
            println("##### CALLERS OF "+nm[k]+" @ "+ep+" #####");
            Set<Address> callerFns=new LinkedHashSet<>();
            ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(ep);
            while(it.hasNext()){
                Reference r=it.next();
                Address from=r.getFromAddress();
                Function cf=getFunctionContaining(from);
                println("  call@"+from+" type="+r.getReferenceType()+
                    " inFn="+(cf!=null?cf.getName()+"@"+cf.getEntryPoint():"?"));
                if(cf!=null) callerFns.add(cf.getEntryPoint());
            }
            for(Address fa:callerFns){
                Function f=getFunctionAt(fa);
                println("----- DECOMP CALLER "+f.getName()+" @ "+fa+" -----");
                DecompileResults rr=di.decompileFunction(f,90,new ConsoleTaskMonitor());
                if(rr!=null&&rr.decompileCompleted())
                    println(rr.getDecompiledFunction().getC());
                else println("(decomp failed)");
            }
        }
    }
}
