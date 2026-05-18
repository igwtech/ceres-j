// REStateSync14.java (task #201) — scan EVERY instruction in the binary for a
// displacement write to the CHARSYS live-pool floats:
//   HP current  = +0x42c   (regen rate +0x41c)
//   PSI current = +0x430   (regen rate +0x420 ; display clamp +0x410)
//   STA current = +0x434   (regen rate +0x424 ; display clamp +0x414)
// The 3 regen ticks FUN_007e87d0/8930/8a20 are the KNOWN local writers.
// Any OTHER function writing these offsets with a value derived from a
// received buffer is the server-driven damage/heal apply path.
// For each non-tick writer we also decompile it so the source of the
// written value is visible.
// Output: docs/re_state_sync_dump14.txt.
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.lang.OperandType;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;
public class REStateSync14 extends GhidraScript {
  DecompInterface d; Listing l; PrintWriter pw;
  // offsets of interest
  long[] OFF = {0x42c,0x430,0x434,0x41c,0x420,0x424,0x410,0x414,0x3f4,0x3f8,0x3fc,0x40c};
  Set<String> KNOWN = new HashSet<>(Arrays.asList("FUN_007e87d0","FUN_007e8930","FUN_007e8a20","FUN_007e7c00"));
  protected void run() throws Exception {
    d=new DecompInterface(); d.setOptions(new DecompileOptions()); d.openProgram(currentProgram);
    l=currentProgram.getListing();
    Set<Long> want=new HashSet<>(); for(long o:OFF) want.add(o);
    Map<String,Set<Long>> hits=new TreeMap<>(); // funcName@entry -> offsets it writes
    InstructionIterator it=l.getInstructions(true);
    while(it.hasNext()){ Instruction ins=it.next();
      String mn=ins.getMnemonicString();
      // only store-class mnemonics that target memory: mov/movss/fst*/and/or to [reg+disp]
      boolean store = mn.startsWith("MOV")||mn.startsWith("FST")||mn.startsWith("FIST")||mn.equals("AND")||mn.equals("OR")||mn.equals("ADD")||mn.equals("SUB");
      if(!store) continue;
      int nops=ins.getNumOperands();
      for(int op=0; op<nops; op++){
        int ot=ins.getOperandType(op);
        if((ot&OperandType.ADDRESS)!=0) continue;
        Object[] o=ins.getOpObjects(op);
        for(Object x:o){ if(x instanceof Scalar){ long v=((Scalar)x).getUnsignedValue();
          if(want.contains(v)){
            // dest operand only (operand 0 for x86 store is the memory dest)
            if(op==0){
              Function fn=l.getFunctionContaining(ins.getAddress());
              String key=(fn!=null?fn.getName()+"@"+fn.getEntryPoint():"<none>@"+ins.getAddress());
              hits.computeIfAbsent(key,k->new TreeSet<>()).add(v);
            }
          }
        }}
      }
    }
    try(PrintWriter w=new PrintWriter("/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_state_sync_dump14.txt")){ pw=w;
      pw.println("### ALL functions writing CHARSYS pool offsets (op0 = mem dest) ###");
      for(Map.Entry<String,Set<Long>> e:hits.entrySet()){
        StringBuilder sb=new StringBuilder();
        for(long v:e.getValue()) sb.append(String.format("+0x%x ",v));
        pw.println("  "+e.getKey()+"  ->  "+sb);
      }
      pw.println();
      // decompile every NON-tick writer that touches the CURRENT-pool floats
      Set<String> toDec=new LinkedHashSet<>();
      for(Map.Entry<String,Set<Long>> e:hits.entrySet()){
        String fname=e.getKey().split("@")[0];
        if(KNOWN.contains(fname)) continue;
        Set<Long> s=e.getValue();
        if(s.contains(0x42cL)||s.contains(0x430L)||s.contains(0x434L)||s.contains(0x410L)||s.contains(0x414L)||s.contains(0x3f4L)||s.contains(0x3f8L)||s.contains(0x3fcL))
          toDec.add(e.getKey());
      }
      for(String k:toDec){
        Address ea=currentProgram.getAddressFactory().getAddress(k.split("@")[1]);
        Function fn=l.getFunctionAt(ea); if(fn==null)fn=l.getFunctionContaining(ea);
        pw.println("======================================================");
        pw.println("FUNCTION "+(fn!=null?fn.getName():k)+" @ "+(fn!=null?fn.getEntryPoint():ea));
        pw.println("======================================================");
        if(fn!=null){ DecompileResults r=d.decompileFunction(fn,200,new ConsoleTaskMonitor());
          if(r!=null&&r.decompileCompleted()) pw.println(r.getDecompiledFunction().getC()); else pw.println("(decompile failed)"); }
        pw.println();
      }
    }
    println("done");
  }
}
