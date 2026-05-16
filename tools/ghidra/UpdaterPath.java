import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class UpdaterPath extends GhidraScript {
    DecompInterface di;
    void dc(long va,String tag){
        Function f=getFunctionAt(toAddr(va));
        if(f==null){ println("(no fn at "+Long.toHexString(va)+")"); return; }
        println("===== "+tag+" "+f.getName()+" @ "+toAddr(va)+" =====");
        DecompileResults r=di.decompileFunction(f,120,new ConsoleTaskMonitor());
        if(r!=null&&r.decompileCompleted()) println(r.getDecompiledFunction().getC());
        else println("(decomp failed)");
    }
    @Override protected void run() throws Exception {
        di=new DecompInterface(); di.setOptions(new DecompileOptions()); di.openProgram(currentProgram);
        // updater caller chain: thunk_FUN_0043f7d0@00414f01 called from FUN_009ac2e8@009ac2e8
        dc(0x009ac2e8L,"UPDATER-CALLER");
        // who calls FUN_009ac2e8 ?
        println("=== xrefs to FUN_009ac2e8 ===");
        ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(toAddr(0x009ac2e8L));
        Set<Address> up=new LinkedHashSet<>();
        while(it.hasNext()){ Reference r=it.next(); Function cf=getFunctionContaining(r.getFromAddress());
            println("  from "+r.getFromAddress()+" type="+r.getReferenceType()+" inFn="+
              (cf!=null?cf.getName()+"@"+cf.getEntryPoint():"?"));
            if(cf!=null) up.add(cf.getEntryPoint()); }
        for(Address u:up) dc(u.getOffset(),"UP1");
        // Also: show the call site to thunk_FUN_0043f7d0 with args
        println("=== disasm around updater thunk call 0x009ac3de ===");
        Address a=toAddr(0x009ac390L); Listing l=currentProgram.getListing();
        while(a.getOffset()<0x009ac420L){
            Instruction ins=l.getInstructionAt(a);
            if(ins==null){a=a.add(1);continue;}
            byte[] b=ins.getBytes(); StringBuilder sb=new StringBuilder();
            for(byte x:b)sb.append(String.format("%02x ",x&0xff));
            println(ins.getAddress()+":  "+ins+"  ["+sb.toString().trim()+"]");
            a=ins.getAddress().add(ins.getLength());
        }
    }
}
