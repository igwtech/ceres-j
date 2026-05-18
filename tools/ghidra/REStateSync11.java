// REStateSync11.java (task #194) — decompile the LC_RESTORECHAR ctor
// callers FUN_00842a80 / FUN_00840ee0 / FUN_00840e40 to locate the LC
// message factory. Output: docs/re_state_sync_dump11.txt.
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;
public class REStateSync11 extends GhidraScript {
  DecompInterface d; Listing l; PrintWriter pw;
  protected void run() throws Exception {
    d=new DecompInterface(); d.setOptions(new DecompileOptions()); d.openProgram(currentProgram);
    l=currentProgram.getListing();
    try(PrintWriter w=new PrintWriter("/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump11.txt")){ pw=w;
      for(String h:new String[]{"00842a80","00840ee0","00840e40"}) { dec(h,200); refs(h); }
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
        for(Reference r2 : iter(currentProgram.getReferenceManager().getReferencesTo(cf.getEntryPoint()))){
          Function cf2=l.getFunctionContaining(r2.getFromAddress());
          pw.println("     via-thunk from="+r2.getFromAddress()+(cf2!=null?(" in "+cf2.getName()+"@"+cf2.getEntryPoint()):"")); }
      }
    } pw.println();
  }
  List<Reference> iter(ReferenceIterator it){ List<Reference> r=new ArrayList<>(); while(it.hasNext())r.add(it.next()); return r; }
  void dec(String h,int s){ Address a=currentProgram.getAddressFactory().getAddress(h);
    Function fn=l.getFunctionAt(a); if(fn==null)fn=l.getFunctionContaining(a);
    pw.println("======================================================");
    pw.println("FUNCTION "+(fn!=null?fn.getName():("FUN_"+h))+" @ "+(fn!=null?fn.getEntryPoint():a));
    pw.println("======================================================");
    if(fn!=null){ DecompileResults r=d.decompileFunction(fn,s,new ConsoleTaskMonitor());
      if(r!=null&&r.decompileCompleted()) pw.println(r.getDecompiledFunction().getC()); else pw.println("(decompile failed)"); }
    else pw.println("(no fn)"); pw.println(); }
}
