// Diagnostic: try FillOutStructureHelper on the first N thiscall functions, several ways,
// and report which (if any) recovers fields. Read-only-ish (creates throwaway structs).
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.app.decompiler.util.FillOutStructureHelper;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;

public class ProbeStruct extends GhidraScript {
    @Override public void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        FillOutStructureHelper helper = new FillOutStructureHelper(currentProgram, monitor);
        Register ecx = currentProgram.getRegister("ECX");
        Address ecxAddr = ecx.getAddress();

        int tried=0, hitParam=0, hitEcx=0, hitNewClass=0;
        FunctionIterator it = currentProgram.getListing().getFunctions(true);
        while (it.hasNext() && tried < 120) {
            Function f = it.next();
            if (f.isThunk()) continue;
            String cc = f.getCallingConventionName();
            boolean thiscall = cc != null && cc.contains("this");
            if (!thiscall) continue;
            tried++;

            // way A: param0 storage, createNew=true, createClass=false
            try {
                Parameter p0 = f.getParameterCount()>0 ? f.getParameter(0) : null;
                if (p0 != null) {
                    Address sa = p0.getVariableStorage().getFirstVarnode().getAddress();
                    HighVariable hv = helper.computeHighVariable(sa, f, decomp);
                    if (hv != null) {
                        Structure s = helper.processStructure(hv, f, true, false, decomp);
                        if (s != null && s.getNumDefinedComponents() > 0) {
                            hitParam++;
                            if (hitParam <= 5) println("  A param  "+f.getName()+" @"+f.getEntryPoint()+
                                " fields="+s.getNumDefinedComponents()+" size=0x"+Integer.toHexString(s.getLength()));
                        }
                    }
                }
            } catch (Exception ex) { if (tried<=3) println("A err "+f.getName()+": "+ex); }

            // way B: ECX storage, createNew=true, createClass=true
            try {
                HighVariable hv = helper.computeHighVariable(ecxAddr, f, decomp);
                if (hv != null) {
                    Structure s = helper.processStructure(hv, f, true, true, decomp);
                    if (s != null && s.getNumDefinedComponents() > 0) {
                        hitEcx++;
                        if (hitEcx <= 5) println("  B ecx    "+f.getName()+" @"+f.getEntryPoint()+
                            " fields="+s.getNumDefinedComponents()+" size=0x"+Integer.toHexString(s.getLength()));
                    }
                }
            } catch (Exception ex) { if (tried<=3) println("B err "+f.getName()+": "+ex); }
        }
        decomp.dispose();
        println("=== ProbeStruct ===");
        println("thiscall fns tried : " + tried);
        println("A (param storage) recovered fields in : " + hitParam);
        println("B (ECX storage)   recovered fields in : " + hitEcx);
    }
}
