// REStateSync19.java (task #201) — pin who SETS the live current-HP anchor
// +0x42c (and +0x430 PSI / +0x434 STA) ABSOLUTELY from a parsed CHARSYS
// buffer, i.e. the bridge from section-2 (+0x40c cur) to the tick's
// working current (+0x42c). Decompile the FUN_0080b8b0 recompute helpers
// invoked right before the HP tick: FUN_0080c930, FUN_007e86b0,
// FUN_007e8bf0, FUN_0080b5d0, FUN_007e87d0(full), FUN_00845820(full again),
// and FUN_007f5460 (heal-to-full). Also dump the section dispatch table
// FUN_00845400 so the section-id -> handler map is explicit.
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
public class REStateSync19 extends GhidraScript {
  DecompInterface d; Listing l; PrintWriter pw;
  protected void run() throws Exception {
    d=new DecompInterface(); d.setOptions(new DecompileOptions()); d.openProgram(currentProgram);
    l=currentProgram.getListing();
    try(PrintWriter w=new PrintWriter("/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump19.txt")){ pw=w;
      for(String h:new String[]{"0080c930","007e86b0","007e8bf0","0080b5d0","007f5460","00845400","00845820","007e87d0","0080c660","007ef260","008447d0"}) dec(h,260);
    }
    println("done");
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
