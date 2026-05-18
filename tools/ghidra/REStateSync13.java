// REStateSync13.java (task #201) — find the NETWORK handler that writes the
// live current-pool float (charsys ~+0x40c../+0x41c..) from a RECEIVED damage/
// heal packet — distinct from the local regen ticks FUN_007e87d0/8930/8a20.
// Strategy:
//  1. Decompile the 3 regen ticks + FUN_007e7c00 to pin the EXACT pool float
//     offsets they clamp (the live HP/PSI/STA floats).
//  2. Decompile FUN_007fca40 (FULLCHARSYSTEM event 0x85 "single HUD value
//     apply") + FUN_007e90c0 (0x8d) + FUN_007f8a20 (0x83) — candidates that
//     write a server-driven current value.
//  3. Walk REFs to those float offsets / to FUN_007fca40 to find the network
//     ingress (a 0x03/0x1f sub-tag, 0x03/0x2d sub-action, or LC message).
//  4. Decompile the 0x03/0x1f sub-dispatcher + 0x03/0x2d handler to pin the
//     wire opcode + byte layout (set vs signed delta).
// Output: docs/re_state_sync_dump13.txt.
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;
public class REStateSync13 extends GhidraScript {
  DecompInterface d; Listing l; PrintWriter pw;
  protected void run() throws Exception {
    d=new DecompInterface(); d.setOptions(new DecompileOptions()); d.openProgram(currentProgram);
    l=currentProgram.getListing();
    try(PrintWriter w=new PrintWriter("/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump13.txt")){ pw=w;
      // (1) regen ticks + the timer-skill delta
      for(String h:new String[]{"007e87d0","007e8930","007e8a20","007e7c00"}){ dec(h,250); }
      // (2) candidate server-driven HUD value appliers from FUN_00803cd0
      for(String h:new String[]{"007fca40","007e90c0","007f8a20","007fb410","00807e50","00841890"}){ dec(h,250); refs(h); }
      // (3) the 0x03/0x1f sub-tag WWORLDMGR per-entity path + 0x03/0x2d
      for(String h:new String[]{"00541f20","005412d0"}){ dec(h,300); }
      println("done");
    }
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
