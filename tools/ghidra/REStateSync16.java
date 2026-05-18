// REStateSync16.java (task #201) — pin the WIRE opcode that delivers
// FULLCHARSYSTEM event 0x93 (the damage applier FUN_007f0f30) and the
// HUD-value events (0x83-0x8d) into FUN_00803cd0.
// FUN_00803cd0 is a C++ vtable method. Find:
//  (a) its data refs (the vftable slot it sits in) + the class vftable addr
//  (b) every CALL site (direct + via thunk) and decompile the immediate caller
//      so we see where param_1 (the event id) comes from — esp. a wire byte.
//  (c) decompile the WWORLDMGR 0x1f sub-dispatcher candidates + the per-entity
//      NetMessage virtual_24 (LSTPLAYER) + the reliable 0x03/0x2d handler so
//      the S->C opcode -> event 0x93 bridge is visible.
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;
public class REStateSync16 extends GhidraScript {
  DecompInterface d; Listing l; PrintWriter pw;
  protected void run() throws Exception {
    d=new DecompInterface(); d.setOptions(new DecompileOptions()); d.openProgram(currentProgram);
    l=currentProgram.getListing();
    try(PrintWriter w=new PrintWriter("/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump16.txt")){ pw=w;
      Address a=addr("00803cd0");
      pw.println("#### ALL refs to FUN_00803cd0 (incl data = vtable slot) ####");
      ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(a);
      List<String> dataRefs=new ArrayList<>(); Set<String> callerFns=new LinkedHashSet<>();
      while(it.hasNext()){ Reference r=it.next(); Address f=r.getFromAddress();
        Function cf=l.getFunctionContaining(f);
        String tag=" from="+f+" "+r.getReferenceType()+(cf!=null?(" in "+cf.getName()+"@"+cf.getEntryPoint()):" <DATA>");
        pw.println(tag);
        if(cf==null) dataRefs.add(f.toString());
        if(cf!=null){
          if(cf.getName().startsWith("thunk_")){
            ReferenceIterator it2=currentProgram.getReferenceManager().getReferencesTo(cf.getEntryPoint());
            while(it2.hasNext()){ Reference r2=it2.next(); Function cf2=l.getFunctionContaining(r2.getFromAddress());
              if(cf2!=null&&!cf2.getName().startsWith("thunk_")) callerFns.add(cf2.getName()+"@"+cf2.getEntryPoint()); }
          } else callerFns.add(cf.getName()+"@"+cf.getEntryPoint());
        }
      }
      pw.println("\n#### data-ref addresses (vftable slots) — dump 0x20 bytes around each ####");
      for(String dr:dataRefs){ Address da=addr(dr.replace("0x","")); pw.println("  @"+da);
        for(int i=-16;i<=16;i+=4){ try{ Address p=da.add(i); long v=currentProgram.getMemory().getInt(p)&0xffffffffL;
          Function tgt=l.getFunctionAt(addr(Long.toHexString(v))); pw.printf("    [%s] = %08x %s%n",p,v,(tgt!=null?tgt.getName():"")); }catch(Exception e){} } }
      pw.println("\n#### non-thunk caller functions of FUN_00803cd0 ####");
      for(String c:callerFns) pw.println("  "+c);
      // decompile each unique non-thunk caller
      Set<String> dd=new LinkedHashSet<>();
      for(String c:callerFns) dd.add(c.split("@")[1]);
      for(String h:dd) dec(h.replace("0x",""),220);
      // candidate WWORLDMGR/2d network handlers
      for(String h:new String[]{"00541f20","005412d0","0069a580","00699fd0"}){ /* already in dump13 */ }
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
