// REStateSync15.java (task #201) — trace the HP/PSI/STA signed-delta appliers
// up to the NETWORK ingress.
//   FUN_007f5770 : HP signed-delta add to +0x3f4/+0x3f8/+0x3fc -> FUN_0080c660 (HUD)
//   FUN_007f0f30 : detailed damage applier (OneShotProtection) -> FUN_0080c660
//   FUN_007f58d0 : STA/secondary +0x410 add
//   FUN_007f1f20 : +0x414 sub then UI 0x85
//   FUN_007f60d0 : +0x414 writer
//   FUN_0043f020 : +0x410 writer
// Walk callers transitively (up to depth 6) printing every hop + decompile,
// stopping when we reach FUN_00541f20 (WWORLDMGR), FUN_00803cd0 (FULLCHARSYSTEM),
// or a 0x1f/0x2d sub-dispatcher.
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;
public class REStateSync15 extends GhidraScript {
  DecompInterface d; Listing l; PrintWriter pw;
  Set<String> seen=new HashSet<>();
  protected void run() throws Exception {
    d=new DecompInterface(); d.setOptions(new DecompileOptions()); d.openProgram(currentProgram);
    l=currentProgram.getListing();
    try(PrintWriter w=new PrintWriter("/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump15.txt")){ pw=w;
      String[] roots={"007f5770","007f0f30","007f58d0","007f1f20","007f60d0","0043f020"};
      for(String r:roots){ pw.println("################ ROOT "+r+" ################"); walk(r,0); pw.println(); }
      // Also decompile the FULLCHARSYSTEM 0x83..0x8d HUD block callees + 0x1f/0x2d
      for(String h:new String[]{"007f8a20","007fca40","007e90c0","0080ac10","00807ed0","0080a9d0"}){ dec(h,180); }
    }
    println("done");
  }
  void walk(String h,int depth){
    if(depth>6) return;
    Address a=currentProgram.getAddressFactory().getAddress(h);
    Function fn=l.getFunctionAt(a); if(fn==null)fn=l.getFunctionContaining(a);
    String fname=fn!=null?fn.getName():("FUN_"+h);
    if(!seen.add(fname+"@"+depth)) ; // allow re-visit at different depths but cap recursion below
    String pad=""; for(int i=0;i<depth;i++) pad+="  ";
    Set<String> callers=new LinkedHashSet<>();
    ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(a);
    while(it.hasNext()){ Reference r=it.next(); if(!r.getReferenceType().isCall()&&!r.getReferenceType().isJump()&&!r.getReferenceType().isData()) ;
      Address f=r.getFromAddress(); Function cf=l.getFunctionContaining(f);
      if(cf==null) continue;
      String cn=cf.getName();
      if(cn.startsWith("thunk_")){
        ReferenceIterator it2=currentProgram.getReferenceManager().getReferencesTo(cf.getEntryPoint());
        while(it2.hasNext()){ Reference r2=it2.next(); Function cf2=l.getFunctionContaining(r2.getFromAddress());
          if(cf2!=null&&!cf2.getName().startsWith("thunk_")) callers.add(cf2.getName()+"@"+cf2.getEntryPoint()); }
      } else callers.add(cn+"@"+cf.getEntryPoint());
    }
    pw.println(pad+"- "+fname+"@"+a+"  callers: "+callers);
    for(String c:callers){
      String ce=c.split("@")[1];
      String cn=c.split("@")[0];
      // stop & decompile at known network ingress points
      boolean stop = cn.equals("FUN_00541f20")||cn.equals("FUN_00803cd0")||cn.equals("FUN_0055ec10")||cn.equals("FUN_005412d0")||cn.equals("FUN_0055c270");
      if(stop){ pw.println(pad+"  >>> NETWORK INGRESS "+c); continue; }
      // avoid infinite loops: only descend into not-yet-expanded funcs
      if(seen.add("EXPAND:"+cn)) walk(ce.replace("0x",""),depth+1);
    }
  }
  void dec(String h,int s){ Address a=currentProgram.getAddressFactory().getAddress(h);
    Function fn=l.getFunctionAt(a); if(fn==null)fn=l.getFunctionContaining(a);
    pw.println("======================================================");
    pw.println("FUNCTION "+(fn!=null?fn.getName():("FUN_"+h))+" @ "+(fn!=null?fn.getEntryPoint():a));
    pw.println("======================================================");
    if(fn!=null){ DecompileResults r=d.decompileFunction(fn,s,new ConsoleTaskMonitor());
      if(r!=null&&r.decompileCompleted()) pw.println(r.getDecompiledFunction().getC()); else pw.println("(decompile failed)"); }
    pw.println(); }
}
