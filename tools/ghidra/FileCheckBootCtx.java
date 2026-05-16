import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class FileCheckBootCtx extends GhidraScript {
    @Override protected void run() throws Exception {
        // 1. disassembly context around the in-client check call site 0x00462b7c
        println("=== context @ 0x00462b7c (caller of in-client check) ===");
        Address a=toAddr(0x00462b40L);
        Listing l=currentProgram.getListing();
        for(int i=0;i<40;i++){
            Instruction ins=l.getInstructionAt(a);
            if(ins==null){ a=a.add(1); continue; }
            byte[] b=ins.getBytes(); StringBuilder sb=new StringBuilder();
            for(byte x:b) sb.append(String.format("%02x ",x&0xff));
            println(ins.getAddress()+":  "+ins+"   ["+sb.toString().trim()+"]");
            a=ins.getAddress().add(ins.getLength());
            if(a.getOffset()>0x00462c20L) break;
        }
        // What function contains 0x00462b7c?
        Function fc=getFunctionContaining(toAddr(0x00462b7cL));
        println("containing fn = "+(fc!=null?fc.getName()+"@"+fc.getEntryPoint():"NONE (undefined region)"));
        // find the enclosing function by scanning backward for push ebp/mov ebp,esp prologue
        // and list xrefs into 0x00462b7c's basic block region
        println("=== xrefs landing near 0x004629xx-0x00462c00 ===");
        ReferenceManager rm=currentProgram.getReferenceManager();
        for(long va=0x00462900L; va<0x00462c00L; va++){
            ReferenceIterator it=rm.getReferencesTo(toAddr(va));
            while(it.hasNext()){
                Reference r=it.next();
                if(r.getReferenceType().isCall()){
                    Function cf=getFunctionContaining(r.getFromAddress());
                    println("  CALL into "+toAddr(va)+" from "+r.getFromAddress()+
                        " inFn="+(cf!=null?cf.getName()+"@"+cf.getEntryPoint():"?"));
                }
            }
        }
        // 2. decompile FUN_009ac2e8 (updater caller)
        DecompInterface di=new DecompInterface(); di.setOptions(new DecompileOptions());
        di.openProgram(currentProgram);
        Function f=getFunctionAt(toAddr(0x009ac2e8L));
        if(f!=null){
            println("=== DECOMP FUN_009ac2e8 (updater caller) ===");
            DecompileResults rr=di.decompileFunction(f,90,new ConsoleTaskMonitor());
            if(rr!=null&&rr.decompileCompleted()) println(rr.getDecompiledFunction().getC());
        }
    }
}
