// FindCallers.java — Ghidra headless script.
//
// Find the REAL callers of specific functions we care about in the
// login/sync flow, walking transparently through thunk wrappers.
//
// Why "real" callers matter: when function X is called indirectly via
// a thunk T (T is a tiny function that does nothing but jump to X),
// Ghidra's reference graph reports T as the caller of X, not the
// function that actually invoked T. Our first pass through this script
// returned only thunks, which was useless for reconstructing the
// dispatch tree. This version walks backward through thunks until it
// reaches real code.
//
// Targets chosen to map the login/sync dispatch tree:
//   FUN_00559920  — gamedata dispatcher (cases 1, 3, 0x19, 0x22, ...)
//   FUN_0055c270  — CharInfo/CharsysInfo sub-dispatcher
//   FUN_0055bdc0  — state machine variant (Joining session logging)
//   FUN_00558950  — world-change/command-id handler
//   FUN_0055b6f0  — state machine variant (active on tick path)
//   FUN_0055aa30  — NetMgr message pump (case 0x05 is the unblock point)
//   FUN_0055a5e0  — NetHost bootstrap variant 1
//   FUN_005592d0  — NetHost bootstrap variant 2 (actually runs per Init.Log)
//   FUN_00559520  — "WorldClient: Joining session" handler
//   FUN_00557b90  — WorldClient constructor (who builds the param_2 struct?)
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient.exe -noanalysis \
//       -scriptPath /home/javier/Documents/Projects/Neocron/ceres-j/tools/ghidra \
//       -postScript FindCallers.java

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.util.task.TaskMonitor;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;

public class FindCallers extends GhidraScript {

    private static final String[] TARGETS = {
        "00559920",  // gamedata dispatcher
        "0055c270",  // CharInfo/CharsysInfo handler
        "0055bdc0",  // state machine variant (Joining session log)
        "00558950",  // world-change handler
        "0055b6f0",  // state machine variant (active)
        "0055aa30",  // NetMgr msg pump — case 0x05 "Client accepted" unblock
        "0055a5e0",  // NetHost bootstrap variant 1
        "005592d0",  // NetHost bootstrap variant 2 (actually runs)
        "00559520",  // "Joining session" handler
        "00557b90",  // WorldClient constructor
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/call_graph.txt";

    // Safety valve: stop walking if thunk chains get too deep. Real
    // thunks are rarely more than 2-3 deep; anything beyond 16 is
    // almost certainly an instrumentation/runtime thunk we don't care
    // about.
    private static final int MAX_THUNK_DEPTH = 16;

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();
        TaskMonitor monitor = new ConsoleTaskMonitor();

        // Collect unique real callers across all targets so we decompile
        // each caller only once.
        TreeMap<Address, Function> realCallers = new TreeMap<>();

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# Call graph for WorldClient state-machine functions");
            pw.println("# Real callers resolved by walking transparently through thunks");
            pw.println();

            for (String hex : TARGETS) {
                Address targetAddr = currentProgram.getAddressFactory()
                        .getAddress(hex);
                Function target = listing.getFunctionAt(targetAddr);
                String targetName = target != null
                        ? target.getName() : ("FUN_" + hex);

                pw.println("======================================================");
                pw.println("TARGET " + targetName + " @ " + targetAddr);
                pw.println("======================================================");

                // --- direct references (for debugging / cross-checking) ---
                pw.println("Direct references (raw, not thunk-resolved):");
                ReferenceIterator refs = refMgr.getReferencesTo(targetAddr);
                Set<String> directLines = new LinkedHashSet<>();
                while (refs.hasNext()) {
                    Reference r = refs.next();
                    Address from = r.getFromAddress();
                    Function caller = listing.getFunctionContaining(from);
                    String callerName;
                    if (caller == null) {
                        callerName = "(orphan " + from + ")";
                    } else {
                        String thunkNote = caller.isThunk() ? " [THUNK]" : "";
                        callerName = caller.getName() + " @ "
                                + caller.getEntryPoint() + thunkNote;
                    }
                    directLines.add("  " + from + " in " + callerName
                            + " [" + r.getReferenceType() + "]");
                }
                for (String line : directLines) {
                    pw.println(line);
                }
                pw.println();

                // --- also probe the primary thunk, if any, to discover
                // whether it's called via code or via a data pointer
                // (vtable / function-pointer array) ---
                Set<Address> vtableSlots = new LinkedHashSet<>();
                if (!directLines.isEmpty()) {
                    pw.println("Thunk probe (references to each thunk found above):");
                    refs = refMgr.getReferencesTo(targetAddr);
                    while (refs.hasNext()) {
                        Reference r = refs.next();
                        Function caller = listing.getFunctionContaining(r.getFromAddress());
                        if (caller == null || !caller.isThunk()) continue;
                        Address thunkEp = caller.getEntryPoint();
                        ReferenceIterator thunkRefs = refMgr.getReferencesTo(thunkEp);
                        int codeRefs = 0, dataRefs = 0, otherRefs = 0;
                        Set<String> thunkRefLines = new LinkedHashSet<>();
                        while (thunkRefs.hasNext()) {
                            Reference tr = thunkRefs.next();
                            Address from = tr.getFromAddress();
                            String kind;
                            Function trCaller = listing.getFunctionContaining(from);
                            if (trCaller != null) {
                                codeRefs++;
                                kind = "CODE inside " + trCaller.getName()
                                        + (trCaller.isThunk() ? " [THUNK]" : "");
                            } else if (listing.getDataContaining(from) != null) {
                                dataRefs++;
                                kind = "DATA @ " + from
                                        + " (vtable/fn-ptr entry)";
                                vtableSlots.add(from);
                            } else {
                                otherRefs++;
                                kind = "ORPHAN @ " + from;
                            }
                            thunkRefLines.add("    " + tr.getReferenceType()
                                    + " from " + from + " → " + kind);
                        }
                        pw.println("  thunk " + caller.getName() + " @ " + thunkEp
                                + ": code=" + codeRefs + " data=" + dataRefs
                                + " orphan=" + otherRefs);
                        for (String line : thunkRefLines) {
                            pw.println(line);
                        }
                    }
                    pw.println();
                }

                // --- for each data slot (vtable entry) discovered above,
                // look up who references THAT address. This unwraps one
                // level of indirect dispatch: `CALL [vtable_base + offset]`
                // where `vtable_base + offset == slot_addr`. ---
                if (!vtableSlots.isEmpty()) {
                    pw.println("Vtable-slot probe (references to data slots above):");
                    for (Address slot : vtableSlots) {
                        pw.println("  slot " + slot + ":");
                        // Dump the nearby slots to help identify the vtable/class
                        for (long off = -0x20; off <= 0x20; off += 4) {
                            Address probe;
                            try {
                                probe = slot.add(off);
                            } catch (Exception e) {
                                continue;
                            }
                            Reference[] forward = refMgr.getReferencesFrom(probe);
                            for (Reference fr : forward) {
                                Address to = fr.getToAddress();
                                Function fn = listing.getFunctionAt(to);
                                String label;
                                if (fn != null) {
                                    label = fn.getName() + " @ " + to
                                            + (fn.isThunk() ? " [THUNK]" : "");
                                } else {
                                    label = "(no fn at " + to + ")";
                                }
                                pw.println("    neighbor " + probe + " → " + label);
                            }
                        }
                        ReferenceIterator slotRefs = refMgr.getReferencesTo(slot);
                        int slotCodeRefs = 0, slotDataRefs = 0, slotOrphanRefs = 0;
                        Set<String> slotRefLines = new LinkedHashSet<>();
                        while (slotRefs.hasNext()) {
                            Reference sr = slotRefs.next();
                            Address from = sr.getFromAddress();
                            Function srCaller = listing.getFunctionContaining(from);
                            if (srCaller != null) {
                                slotCodeRefs++;
                                slotRefLines.add("    " + sr.getReferenceType()
                                        + " from " + from + " in "
                                        + srCaller.getName()
                                        + (srCaller.isThunk() ? " [THUNK]" : ""));
                            } else if (listing.getDataContaining(from) != null) {
                                slotDataRefs++;
                                slotRefLines.add("    DATA " + sr.getReferenceType()
                                        + " from " + from);
                            } else {
                                slotOrphanRefs++;
                                slotRefLines.add("    ORPHAN " + sr.getReferenceType()
                                        + " from " + from);
                            }
                        }
                        pw.println("    slot refs: code=" + slotCodeRefs
                                + " data=" + slotDataRefs
                                + " orphan=" + slotOrphanRefs);
                        for (String line : slotRefLines) {
                            pw.println(line);
                        }
                    }
                    pw.println();
                }

                // --- resolved (non-thunk) callers via recursive walk ---
                if (target != null) {
                    Set<Function> resolved = resolveRealCallers(target, monitor);
                    pw.println("Resolved non-thunk callers (" + resolved.size() + "):");
                    if (resolved.isEmpty()) {
                        pw.println("  (none found — target has no real callers, or");
                        pw.println("   is reached only via indirect call/vtable)");
                    } else {
                        for (Function rc : resolved) {
                            pw.println("  " + rc.getName() + " @ " + rc.getEntryPoint());
                            realCallers.putIfAbsent(rc.getEntryPoint(), rc);
                        }
                    }
                } else {
                    pw.println("Resolved callers: target function not found at " + targetAddr);
                }
                pw.println();

                println("target " + targetName + " direct=" + directLines.size()
                        + " resolved=" + (target != null
                                ? resolveRealCallers(target, monitor).size() : 0));
            }

            pw.println();
            pw.println("======================================================");
            pw.println("UNIQUE REAL-CALLER DECOMPILATIONS (" + realCallers.size() + ")");
            pw.println("======================================================");
            pw.println();
            for (Function fn : realCallers.values()) {
                pw.println("------------------------------------------------------");
                pw.println("CALLER " + fn.getName() + " @ " + fn.getEntryPoint());
                pw.println("------------------------------------------------------");
                DecompileResults res = decomp.decompileFunction(
                        fn, 120, monitor);
                if (res != null && res.decompileCompleted()) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("(decompile failed)");
                }
                pw.println();
            }
        }
        println("Wrote call graph with " + realCallers.size() + " unique real callers");
    }

    /**
     * Walk backward from a target function to its real (non-thunk)
     * callers, following thunk chains transparently.
     *
     * <p>Implementation note: we use the raw reference graph
     * ({@link ReferenceManager#getReferencesTo(Address)}) instead of
     * {@link Function#getCallingFunctions(TaskMonitor)} because the
     * latter appears to return an empty set for thunk-targeted
     * functions in our binary. The reference iterator does find the
     * UNCONDITIONAL_JUMP references from thunks, so we can walk the
     * thunk chain by iterating references-to at each level.
     *
     * <p>Algorithm: start with the target's address in the queue.
     * For each queued address, iterate its incoming references; for
     * each reference's containing function, if it's a thunk add its
     * entry address to the queue (to walk one more level), otherwise
     * record it as a real caller. Continue until queue is empty.
     * {@code visited} prevents cycles; a safety-net iteration cap
     * prevents runaway walks.
     */
    private Set<Function> resolveRealCallers(Function target, TaskMonitor monitor) {
        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        Set<Function> result = new LinkedHashSet<>();
        Set<Address> visited = new HashSet<>();
        Deque<Address> queue = new ArrayDeque<>();

        queue.add(target.getEntryPoint());
        visited.add(target.getEntryPoint());

        int iterations = 0;
        while (!queue.isEmpty() && iterations < 4096) {
            iterations++;
            Address current = queue.poll();
            ReferenceIterator refs = refMgr.getReferencesTo(current);
            while (refs.hasNext()) {
                Reference r = refs.next();
                Function caller = listing.getFunctionContaining(r.getFromAddress());
                if (caller == null) {
                    // Reference from outside any defined function. Walk
                    // backward a small amount to find the nearest
                    // function start — this catches code that Ghidra
                    // didn't identify as part of a function body.
                    long off = 0;
                    while (off < 0x400 && caller == null) {
                        Address probe = r.getFromAddress().subtract(off);
                        caller = listing.getFunctionAt(probe);
                        off++;
                    }
                    if (caller == null) continue;
                }
                Address callerEp = caller.getEntryPoint();
                if (!visited.add(callerEp)) continue;

                if (caller.isThunk()) {
                    // Walk one more level — whoever calls this thunk
                    // is semantically calling our target.
                    queue.add(callerEp);
                } else {
                    // Real caller.
                    result.add(caller);
                }
            }
        }
        return result;
    }
}
