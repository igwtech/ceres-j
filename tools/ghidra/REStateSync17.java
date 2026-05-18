// REStateSync17.java (task #201) — FUN_00803cd0 is only reached via the thunk
// at 0040d418. Find every ref to that thunk (calls + data/vtable), dump the
// vftable slot, and decompile the immediate non-thunk callers so we see where
// the event id (param_1) comes from on the network path. Then decompile the
// CHARSYS C++ message receivers FUN_00841dc0/008033d0/008034d0/00841bc0/
// 00841b30/007fc9e0/007fcaf0 (the LC apply path) + FUN_008420f0 loop and the
// FUN_00840ee0 LC factory to bridge wire-type -> event 0x93.
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;
public class REStateSync17 extends GhidraScript {
  DecompInterface d; Listing l; PrintWriter pw;
  protected void run() throws Exception {
    d=new DecompInterface(); d.setOptions(new DecompileOptions()); d.openProgram(currentProgram);
    l=currentProgram.getListing();
    try(PrintWriter w=new PrintWriter("/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump17.txt")){ pw=w;
      Address th=addr("0040d418");
      pw.println("#### refs to thunk_FUN_00803cd0 @ 0040d418 ####");
      Set<String> callers=new LinkedHashSet<>(); List<Address> dataRefs=new ArrayList<>();
      ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(th);
      while(it.hasNext()){ Reference r=it.next(); Address f=r.getFromAddress(); Function cf=l.getFunctionContaining(f);
        pw.println(" from="+f+" "+r.getReferenceType()+(cf!=null?(" in "+cf.getName()+"@"+cf.getEntryPoint()):" <DATA>"));
        if(cf==null) dataRefs.add(f);
        else if(!cf.getName().startsWith("thunk_")) callers.add(cf.getName()+"@"+cf.getEntryPoint());
      }
      for(Address dr:dataRefs){ pw.println("\n#### vftable around "+dr+" ####");
        for(int i=-0x10;i<=0x40;i+=4){ try{ Address p=dr.add(i); long v=currentProgram.getMemory().getInt(p)&0xffffffffL;
          Function tgt=l.getFunctionAt(addr(Long.toHexString(v))); pw.printf("  [%s]=%08x %s%n",p,v,tgt!=null?tgt.getName():""); }catch(Exception e){} }
        // who references this vftable slot address itself?
        ReferenceIterator vit=currentProgram.getReferenceManager().getReferencesTo(dr);
        pw.println("  refs-to-this-slot:");
        while(vit.hasNext()){ Reference vr=vit.next(); Function vf=l.getFunctionContaining(vr.getFromAddress());
          pw.println("    from="+vr.getFromAddress()+(vf!=null?" in "+vf.getName()+"@"+vf.getEntryPoint():"")); }
      }
      pw.println("\n#### non-thunk callers of the thunk ####");
      for(String c:callers) pw.println("  "+c);
      Set<String> dd=new LinkedHashSet<>(); for(String c:callers) dd.add(c.split("@")[1]);
      for(String h:dd) dec(h.replace("0x",""),220);
      // CHARSYS LC apply path + factory + 0x1f sub-dispatch hints
      for(String h:new String[]{"008033d0","008034d0","00841dc0","00841bc0","00841b30","007fc9e0","007fcaf0","008420f0","00840ee0","00803330","008009f0","0080d790","007e67b0"}) dec(h,220);
      println("done");
    }
  }
  Address addr(String h){ return currentProgram.getAddressFactory().getAddress(h); }
  void dec(String h,int s){ Address a=addr(h);
    Function fn=l.getFunctionAt(a); if(fn==null)fn=l.getFunctionContaining(a);
    pw.println("======================================================");
    pw.println("FUNCTION "+(fn!=null?fn.getName():("FUN_"+h))+" @ "+(fn!=null?fn.getEntryPoint():a));
    pw.println("======================================================");
    if(fn!=null){ DecompileResults r=d.decompileFunction(fn,s,new ConsoleTaskMonitor());
      if(r!=null&&r.decompileCompleted()) pw.println(r.getDecompiledFunction().getC()); else pw.println("(decompile failed)"); }
    pw.println(); }
}
