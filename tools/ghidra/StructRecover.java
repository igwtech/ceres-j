// Per-class structure (member layout) recovery — vtable-driven, merge-accumulated.
//
// FillOutStructureHelper only fills fields when createNewStructure=true (it returns a
// per-FUNCTION struct). So for each RTTI class C (methods listed by its vtable) we:
//   1. for every method: recover a per-function struct, harvest its {offset->dataType},
//      then delete the throwaway struct,
//   2. merge all harvested fields (by offset) into one accumulator struct C under /recovered.
//
// Optional arg: max number of classes (probe). Field names are offset-based.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.app.decompiler.util.FillOutStructureHelper;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.symbol.*;
import java.util.*;
import java.util.regex.*;

public class StructRecover extends GhidraScript {
    private static final Pattern NOISE = Pattern.compile(
        "^(std|stdext|Concurrency|__|_|operator|fuzzer|YAML|FID_)|<|>|\\.DLL|\\.dll", Pattern.CASE_INSENSITIVE);

    @Override public void run() throws Exception {
        int limit = Integer.MAX_VALUE;
        String[] a = getScriptArgs();
        if (a != null && a.length > 0) try { limit = Integer.parseInt(a[0]); } catch (Exception e) {}

        DataTypeManager dtm = currentProgram.getDataTypeManager();
        CategoryPath cat = new CategoryPath("/recovered");
        dtm.createCategory(cat);
        SymbolTable st = currentProgram.getSymbolTable();
        Listing listing = currentProgram.getListing();
        var mem = currentProgram.getMemory();
        int ptrSize = currentProgram.getDefaultPointerSize();
        Register ecx = currentProgram.getRegister("ECX");
        Address ecxAddr = ecx != null ? ecx.getAddress() : null;

        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        FillOutStructureHelper helper = new FillOutStructureHelper(currentProgram, monitor);

        // vtable class -> ordered unique method list
        LinkedHashMap<GhidraClass, List<Function>> classMethods = new LinkedHashMap<>();
        SymbolIterator it = st.getSymbolIterator("vftable", true);
        while (it.hasNext()) {
            Symbol vsym = it.next();
            if (!vsym.getName().equals("vftable")) continue;
            Namespace ns = vsym.getParentNamespace();
            if (!(ns instanceof GhidraClass)) continue;
            if (NOISE.matcher(ns.getName()).find() || NOISE.matcher(ns.getName(true)).find()) continue;
            GhidraClass cls = (GhidraClass) ns;
            List<Function> ms = classMethods.computeIfAbsent(cls, k -> new ArrayList<>());
            Address va = vsym.getAddress();
            for (int i = 0; i < 4096; i++) {
                Address slot = va.add((long) i * ptrSize);
                if (i > 0) { boolean b=false; for (Symbol s: st.getSymbols(slot)) if (s.getSource()!=SourceType.DEFAULT){b=true;break;} if(b) break; }
                long tgt; try { tgt = mem.getInt(slot) & 0xffffffffL; } catch (Exception e){ break; }
                Address ta; try { ta = va.getNewAddress(tgt); } catch (Exception e){ break; }
                MemoryBlock blk = mem.getBlock(ta);
                if (blk == null || !blk.isExecute()) break;
                Function f = listing.getFunctionAt(ta);
                if (f != null && f.isThunk()) { Function tf = f.getThunkedFunction(true); if (tf != null) f = tf; }
                if (f != null && !ms.contains(f)) ms.add(f);
            }
        }
        println("vtable game classes: " + classMethods.size() + (limit<Integer.MAX_VALUE?(" (probe "+limit+")"):""));

        int clsDone=0, classesWithFields=0, fieldsTotal=0, methodsUsed=0;
        List<String> report = new ArrayList<>();
        for (Map.Entry<GhidraClass,List<Function>> e : classMethods.entrySet()) {
            if (monitor.isCancelled() || clsDone >= limit) break;
            GhidraClass cls = e.getKey();
            clsDone++;

            // offset -> chosen datatype (first/largest wins)
            TreeMap<Integer, DataType> fields = new TreeMap<>();
            int used = 0;
            for (Function m : e.getValue()) {
                if (monitor.isCancelled()) break;
                Address storage = null;
                Parameter p0 = m.getParameterCount()>0 ? m.getParameter(0) : null;
                if (p0 != null) try { storage = p0.getVariableStorage().getFirstVarnode().getAddress(); } catch (Exception ig){}
                if (storage == null) storage = ecxAddr;
                if (storage == null) continue;
                HighVariable hv;
                try { hv = helper.computeHighVariable(storage, m, decomp); } catch (Exception ig){ continue; }
                if (hv == null) continue;
                Structure pf;
                try { pf = helper.processStructure(hv, m, true, false, null); } catch (Exception ig){ continue; }
                if (pf == null) continue;
                used++;
                for (DataTypeComponent c : pf.getDefinedComponents()) {
                    int off = c.getOffset();
                    DataType dt = c.getDataType();
                    DataType prev = fields.get(off);
                    if (prev == null || dt.getLength() > prev.getLength()) fields.put(off, dt);
                }
                try { dtm.remove(pf, monitor); } catch (Exception ig){}   // drop throwaway
            }

            // build/replace accumulator struct C
            String sname = cls.getName();
            int end = 0;
            for (Map.Entry<Integer,DataType> fe : fields.entrySet())
                end = Math.max(end, fe.getKey() + Math.max(1, fe.getValue().getLength()));
            int nf = 0, len = 0;
            if (end > 0) {
                StructureDataType s = new StructureDataType(cat, sname, end, dtm);
                for (Map.Entry<Integer,DataType> fe : fields.entrySet()) {
                    int off = fe.getKey(); DataType dt = fe.getValue();
                    int dl = Math.max(1, dt.getLength());
                    if (off + dl > end) continue;
                    try { s.replaceAtOffset(off, dt, dl, "field_0x"+Integer.toHexString(off), null); }
                    catch (Exception ig) {}
                }
                Structure stored = (Structure) dtm.addDataType(s, DataTypeConflictHandler.REPLACE_HANDLER);
                nf = stored.getNumDefinedComponents(); len = stored.getLength();
                classesWithFields++; fieldsTotal += nf;
            }
            methodsUsed += used;
            report.add(String.format("%-40s methods=%-4d size=0x%-5x fields=%d", sname, e.getValue().size(), len, nf));
            if (clsDone % 25 == 0) println("  ... "+clsDone+"/"+classMethods.size()+" classes (fields so far="+fieldsTotal+")");
        }
        decomp.dispose();
        println("=== StructRecover done ===");
        println("classes processed   : " + clsDone);
        println("classes with fields : " + classesWithFields);
        println("methods analyzed    : " + methodsUsed);
        println("fields recovered    : " + fieldsTotal);
        report.stream().sorted((x,y)->fld(y)-fld(x)).limit(60).forEach(this::println);
    }
    private static int fld(String l){ int i=l.lastIndexOf("fields="); try{return Integer.parseInt(l.substring(i+7).trim());}catch(Exception e){return 0;} }
}
