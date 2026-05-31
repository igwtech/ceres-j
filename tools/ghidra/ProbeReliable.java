// ProbeReliable.java — resolve function entries containing guessed addrs, dump prologue bytes,
// read pointer dwords, dump vtable. args: outPath
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.*;
import java.io.PrintWriter;

public class ProbeReliable extends GhidraScript {
    PrintWriter pw;
    Memory mem;
    Listing listing;

    String hexBytes(Address a, int n) {
        StringBuilder sb = new StringBuilder();
        byte[] b = new byte[n];
        try { mem.getBytes(a, b); } catch (Exception e) { return "(read fail)"; }
        for (int i = 0; i < n; i++) sb.append(String.format("%02x ", b[i] & 0xff));
        return sb.toString();
    }

    long readDword(Address a) throws Exception {
        return ((long) mem.getInt(a)) & 0xffffffffL;
    }

    Address va(long v) {
        return currentProgram.getAddressFactory().getAddress(Long.toHexString(v));
    }

    void probe(String label, String hex) throws Exception {
        Address a = va(Long.parseLong(hex, 16));
        pw.println("==================================================");
        pw.println("PROBE " + label + " guessed @ 0x" + hex);
        pw.println("  bytes@guess: " + hexBytes(a, 16));
        Function fc = currentProgram.getFunctionManager().getFunctionContaining(a);
        Function fa = listing.getFunctionAt(a);
        pw.println("  functionAt(guess): " + (fa != null ? (fa.getName() + " @ " + fa.getEntryPoint()) : "null"));
        if (fc != null) {
            Address e = fc.getEntryPoint();
            pw.println("  CONTAINING FUNCTION: " + fc.getName() + " ENTRY @ " + e);
            pw.println("    prologue bytes: " + hexBytes(e, 16));
            pw.println("    callingConv: " + fc.getCallingConventionName());
            pw.println("    signature: " + fc.getPrototypeString(true, false));
            pw.println("    param count: " + fc.getParameterCount());
            for (Parameter p : fc.getParameters()) {
                pw.println("      param " + p.getOrdinal() + ": " + p.getDataType().getName()
                        + " " + p.getName() + " storage=" + p.getVariableStorage());
            }
            pw.println("    return: " + fc.getReturnType().getName() + " storage=" + fc.getReturn().getVariableStorage());
            // disasm first ~24 instrs of entry
            pw.println("    --- entry disasm ---");
            Instruction ins = listing.getInstructionAt(e);
            for (int k = 0; k < 24 && ins != null; k++) {
                pw.println("      " + ins.getAddress() + ":  " + ins.toString());
                ins = ins.getNext();
            }
        } else {
            pw.println("  NO containing function (undefined). Disasm at guess:");
            Instruction ins = listing.getInstructionAt(a);
            for (int k = 0; k < 12 && ins != null; k++) {
                pw.println("      " + ins.getAddress() + ":  " + ins.toString());
                ins = ins.getNext();
            }
        }
        pw.println();
    }

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        String outPath = args[0];
        mem = currentProgram.getMemory();
        listing = currentProgram.getListing();
        try (PrintWriter w = new PrintWriter(outPath)) {
            pw = w;
            // 1. The vtable read at 0x4cfab0
            pw.println("##### VTABLE 0x4cfab0 dword dump (16 slots) #####");
            Address vt = va(0x4cfab0L);
            for (int i = 0; i < 16; i++) {
                Address slot = vt.add(i * 4L);
                long p = readDword(slot);
                Address tgt = va(p);
                Function f = listing.getFunctionContaining(tgt);
                pw.println("  [" + i + "] @" + slot + " -> 0x" + Long.toHexString(p)
                        + "   " + (f != null ? (f.getName() + " entry=" + f.getEntryPoint()) : "(no fn)"));
            }
            pw.println();

            // 2. Probe each guessed address
            probe("app-handler-vtable[0]-target", "4cf9c0");
            probe("ProcessGuaranteedMsg", "4d0f60");
            probe("AddMsgToOOOList", "4cde70");
            probe("AddClient", "4cd1b0");
        }
        println("Wrote " + outPath);
    }
}
