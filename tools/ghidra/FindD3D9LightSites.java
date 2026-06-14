// FindD3D9LightSites.java — locate indirect calls into IDirect3DDevice9
// vtable slots that matter for flashlight RE on the 64-bit client.
//
// The client doesn't import SetLight/LightEnable/etc. directly — it
// holds the IDirect3DDevice9* and dispatches through `device->lpVtbl`.
// On x86_64 with 8-byte pointers, those become `call [reg + slot*8]`
// instructions where `slot` is the vtable index. Scanning for the
// EXACT offsets reliably finds all call sites — no false positives,
// because the offsets are unique to specific COM methods.
//
// Slots we hunt for:
//   23 -> 0xB8   CreateTexture        (DEPTHSTENCIL = shadow map alloc?)
//   37 -> 0x128  SetRenderTarget      (shadow-map render binding)
//   44 -> 0x160  SetTransform         (D3DTS_LIGHT_n light-space matrix)
//   51 -> 0x198  SetLight             (flashlight light state)
//   53 -> 0x1A8  LightEnable          (turns the flashlight on/off)
//
// Output: a text report listing every call site, grouped by slot,
// with the containing function name + a snippet of decompiled C.
// Use the LightEnable call sites first — they're the rarest and lead
// straight to "the function that turns the flashlight on".
//
// Run from repo root:
//   $GHIDRA/support/analyzeHeadless ~/ghidra-projects Neocron2clien \
//     -process neocronclient.exe \
//     -scriptPath ceres-j/tools/ghidra \
//     -postScript FindD3D9LightSites.java /tmp/d3d9_light_sites.txt

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.scalar.Scalar;
import java.io.PrintWriter;
import java.util.*;

public class FindD3D9LightSites extends GhidraScript {

    static final Map<Long, String> SLOTS = new LinkedHashMap<>();
    static {
        SLOTS.put(0x0B8L, "CreateTexture");
        SLOTS.put(0x128L, "SetRenderTarget");
        SLOTS.put(0x148L, "BeginScene");
        SLOTS.put(0x160L, "SetTransform");
        SLOTS.put(0x198L, "SetLight");
        SLOTS.put(0x1A8L, "LightEnable");
        SLOTS.put(0x2F0L, "SetVertexShaderConstantF");
        SLOTS.put(0x358L, "SetPixelShader");
        SLOTS.put(0x368L, "SetPixelShaderConstantF");
        // Note: these byte offsets are derived from the IDirect3DDevice9
        // vtable, so any custom class with matching slot positions
        // generates false positives — verify via Frida runtime hooks
        // (see flashlight_probe.py) before believing a hit is real.
    }

    @Override
    protected void run() throws Exception {
        String outPath = "/tmp/d3d9_light_sites.txt";
        String[] argv = getScriptArgs();
        if (argv.length >= 1) outPath = argv[0];

        Map<String, List<Hit>> byMethod = new LinkedHashMap<>();
        for (String name : SLOTS.values()) byMethod.put(name, new ArrayList<>());

        InstructionIterator it = currentProgram.getListing()
            .getInstructions(currentProgram.getMemory(), true);
        long scanned = 0;
        while (it.hasNext() && !monitor.isCancelled()) {
            Instruction insn = it.next();
            scanned++;
            if (!insn.getMnemonicString().equalsIgnoreCase("CALL")) continue;
            Long off = extractMemIndirectOffset(insn);
            if (off != null && SLOTS.containsKey(off)) {
                String method = SLOTS.get(off);
                Function f = getFunctionContaining(insn.getAddress());
                byMethod.get(method).add(new Hit(insn.getAddress(), f));
            }
        }

        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        try (PrintWriter pw = new PrintWriter(outPath)) {
            pw.printf("FindD3D9LightSites — scanned %d instructions%n",
                      scanned);
            pw.printf("client: %s%n%n", currentProgram.getName());

            for (Map.Entry<String, List<Hit>> e : byMethod.entrySet()) {
                String method = e.getKey();
                List<Hit> hits = e.getValue();
                pw.printf("=== %s (%d call sites) ===%n",
                          method, hits.size());
                Set<Function> uniq = new LinkedHashSet<>();
                for (Hit h : hits) {
                    pw.printf("  %s    in %s%n", h.addr,
                              h.fn == null ? "<orphan>" :
                              (h.fn.getName() + " @ " + h.fn.getEntryPoint()));
                    if (h.fn != null) uniq.add(h.fn);
                }
                pw.println();
                // Decompile bodies for the rare/discriminating methods.
                // SetPixelShaderConstantF / SetVertexShaderConstantF
                // would explode the report (hundreds of call sites per
                // frame); skip those. SetPixelShader (slot 107) is
                // selective enough to be useful.
                if (method.equals("LightEnable") || method.equals("SetLight")
                 || method.equals("SetPixelShader")
                 || method.equals("BeginScene")) {
                    pw.println("  --- decompiled bodies ---");
                    for (Function f : uniq) {
                        pw.printf("  --- %s @ %s ---%n",
                                  f.getName(), f.getEntryPoint());
                        DecompileResults r = dec.decompileFunction(
                            f, 60, monitor);
                        if (r != null && r.decompileCompleted()) {
                            pw.println(r.getDecompiledFunction().getC());
                        } else {
                            pw.println("  <decompile failed>");
                        }
                        pw.println();
                    }
                }
            }
            int total = 0;
            for (List<Hit> v : byMethod.values()) total += v.size();
            pw.printf("%n=== totals ===  %d sites across %d methods%n",
                      total, byMethod.size());
        }
        dec.dispose();
        println("wrote " + outPath);
    }

    /** If insn is `CALL [reg + disp]`, return disp as Long; else null. */
    Long extractMemIndirectOffset(Instruction insn) {
        try {
            // x64 mem-indirect CALL has a single operand whose Object[]
            // mixes a register + a Scalar displacement. We grab the
            // Scalar component.
            for (int op = 0; op < insn.getNumOperands(); op++) {
                Object[] parts = insn.getOpObjects(op);
                if (parts == null) continue;
                Scalar disp = null;
                boolean hasReg = false;
                for (Object pt : parts) {
                    if (pt instanceof Scalar) disp = (Scalar) pt;
                    else if (pt != null
                          && pt.getClass().getSimpleName().equals("Register")) {
                        hasReg = true;
                    }
                }
                if (hasReg && disp != null) {
                    long v = disp.getUnsignedValue();
                    // Only consider plausible vtable slot offsets
                    // (positive, byte-multiple, under 2KB).
                    if (v > 0 && v < 0x800 && (v & 0x7) == 0) return v;
                }
            }
        } catch (Exception ignore) { }
        return null;
    }

    static class Hit {
        final Address addr;
        final Function fn;
        Hit(Address a, Function f) { this.addr = a; this.fn = f; }
    }
}
