// REStateSync12.java (task #194) — trace the LC factory wrapper
// FUN_008420f0 up to its FULLCHARSYSTEM-dispatcher caller (event 0xb4).
// Output: docs/re_state_sync_dump12.txt.
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;
public class REStateSync12 extends GhidraScript {
  DecompInterface d; Listing l; PrintWriter pw;
  protected void run() throws Exception {
    d=new DecompInterface(); d.setOptions(new DecompileOptions()); d.openProgram(currentProgram);
    l=currentProgram.getListing();
    try(PrintWriter w=new PrintWriter("/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump12.txt")){ pw=w;
      // FUN_008420f0 calls the LC factory FUN_00840ee0. Trace it up to net.
      String[] chain={"008420f0","008408a0","00840a40","00840bc0","0083f720"};
      for(String h:chain){ dec(h,200); refs(h); }
    }
    println("done");
  }
  void refs(String h){ Address a=currentProgram.getAddressFactory().getAddress(h);
    pw.println("#### REFS TO "+h+" ####");
    ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(a);
    while(it.hasNext()){ Reference r=it.next(); Address f=r.getFromAddress();
      Function cf=l.getFunctionContaining(f);
      pw.println("  from="+f+" "+r.getReferenceType()+(cf!=null?(" in "+cf.getName()+"@"+cf.getEntryPoint()):" <data>"));
      if(cf!=null&&cf.getName().startsWith("thunk_")){
        ReferenceIterator it2=currentProgram.getReferenceManager().getReferencesTo(cf.getEntryPoint());
        while(it2.hasNext()){ Reference r2=it2.next(); Function cf2=l.getFunctionContaining(r2.getFromAddress());
          pw.println("     via-thunk from="+r2.getFromAddress()+(cf2!=null?(" in "+cf2.getName()+"@"+cf2.getEntryPoint()):"")); } }
    } pw.println();
  }
  void dec(String h,int s){ Address a=currentProgram.getAddressFactory().getAddress(h);
    Function fn=l.getFunctionAt(a); if(fn==null)fn=l.getFunctionContaining(a);
    pw.println("======================================================");
    pw.println("FUNCTION "+(fn!=null?fn.getName():("FUN_"+h))+" @ "+(fn!=null?fn.getEntryPoint():a));
    pw.println("======================================================");
    if(fn!=null){ DecompileResults r=d.decompileFunction(fn,s,new ConsoleTaskMonitor());
      if(r!=null&&r.decompileCompleted()) pw.println(r.getDecompiledFunction().getC()); else pw.println("(decompile failed)"); }
    else pw.println("(no fn)"); pw.println(); }
}
