// REStateSync18.java (task #201) — pin the WIRE opcode delivering the
// per-stat pool-delta parser FUN_007fcaf0 / FUN_008009f0.
//  - all callers of FUN_007fcaf0 and FUN_008009f0 (decompiled)
//  - FUN_00803cd0 cases 0x6e (live CHARSYS parse) + 0xb4 (LC stream loop)
//    are in dump14; here we decompile the FULLCHARSYSTEM event SENDERS:
//    every function that calls the vtable slot 0x00a54788 indirectly via
//    (*(*obj+0x10))(evt,...) — i.e. callers of thunk_FUN_00803cd0 PLUS
//    the WWORLDMGR/0x1f/0x2d entry FUN_00541f20 path and the per-entity
//    NetMessage virtual handlers (vt+0x18 / vt+0x24).
//  - decompile FUN_007f94f0 (value combine used by FUN_008009f0) to learn
//    set-vs-delta semantics of param_2.
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;
public class REStateSync18 extends GhidraScript {
  DecompInterface d; Listing l; PrintWriter pw;
  protected void run() throws Exception {
    d=new DecompInterface(); d.setOptions(new DecompileOptions()); d.openProgram(currentProgram);
    l=currentProgram.getListing();
    try(PrintWriter w=new PrintWriter("/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump18.txt")){ pw=w;
      for(String r:new String[]{"007fcaf0","008009f0"}){ refs(r); }
      for(String h:new String[]{"007f94f0","007fcaf0","008009f0","00807ed0","0080ac10"}) dec(h,200);
      // callers of FUN_007fcaf0 transitively up to the wire
      walk("007fcaf0",0,new HashSet<>());
      println("done");
    }
  }
  Address addr(String h){ return currentProgram.getAddressFactory().getAddress(h.replace("0x","")); }
  void refs(String h){ Address a=addr(h); pw.println("#### REFS TO "+h+" ####");
    ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(a);
    while(it.hasNext()){ Reference r=it.next(); Address f=r.getFromAddress(); Function cf=l.getFunctionContaining(f);
      pw.println("  from="+f+" "+r.getReferenceType()+(cf!=null?(" in "+cf.getName()+"@"+cf.getEntryPoint()):" <DATA>"));
      if(cf!=null&&cf.getName().startsWith("thunk_")){
        ReferenceIterator it2=currentProgram.getReferenceManager().getReferencesTo(cf.getEntryPoint());
        while(it2.hasNext()){ Reference r2=it2.next(); Function cf2=l.getFunctionContaining(r2.getFromAddress());
          pw.println("     via-thunk from="+r2.getFromAddress()+(cf2!=null?(" in "+cf2.getName()+"@"+cf2.getEntryPoint()):"")); } } }
    pw.println(); }
  void walk(String h,int depth,Set<String> seen){
    if(depth>5) return; Address a=addr(h); Function fn=l.getFunctionContaining(a);
    String fname=fn!=null?fn.getName():("FUN_"+h); String pad=""; for(int i=0;i<depth;i++) pad+="  ";
    Set<String> callers=new LinkedHashSet<>();
    ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(a);
    while(it.hasNext()){ Reference r=it.next(); Function cf=l.getFunctionContaining(r.getFromAddress());
      if(cf==null) continue;
      if(cf.getName().startsWith("thunk_")){
        ReferenceIterator it2=currentProgram.getReferenceManager().getReferencesTo(cf.getEntryPoint());
        while(it2.hasNext()){ Reference r2=it2.next(); Function cf2=l.getFunctionContaining(r2.getFromAddress());
          if(cf2!=null&&!cf2.getName().startsWith("thunk_")) callers.add(cf2.getName()+"@"+cf2.getEntryPoint()); }
      } else callers.add(cf.getName()+"@"+cf.getEntryPoint());
    }
    pw.println(pad+"- "+fname+" callers: "+callers);
    for(String c:callers){ String cn=c.split("@")[0];
      if(cn.equals("FUN_00541f20")||cn.equals("FUN_00803cd0")||cn.equals("FUN_0055ec10")){ pw.println(pad+"  >>> INGRESS "+c); continue; }
      if(seen.add(cn)) walk(c.split("@")[1],depth+1,seen);
    }
  }
  void dec(String h,int s){ Address a=addr(h);
    Function fn=l.getFunctionAt(a); if(fn==null)fn=l.getFunctionContaining(a);
    pw.println("======================================================");
    pw.println("FUNCTION "+(fn!=null?fn.getName():("FUN_"+h))+" @ "+(fn!=null?fn.getEntryPoint():a));
    pw.println("======================================================");
    if(fn!=null){ DecompileResults r=d.decompileFunction(fn,s,new ConsoleTaskMonitor());
      if(r!=null&&r.decompileCompleted()) pw.println(r.getDecompiledFunction().getC()); else pw.println("(decompile failed)"); }
    pw.println(); }
}
