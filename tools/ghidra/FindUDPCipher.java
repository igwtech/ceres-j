// FindUDPCipher.java — Ghidra headless script.
//
// Trace the UDP send/recv path to find the REAL cipher function.
// ObfuscateStreamBuf was proven NOT to be the wire cipher.
//
// Strategy: find functions that reference WinSock send/recv functions
// (sendto, send, WSASend, recvfrom, recv, WSARecv) and trace backward
// to find where encryption/decryption happens. Also look for common
// crypto patterns: RC4 (sbox init 0..255), XOR loops, key schedule.
//
// Additionally search for functions referencing the password
// "xfghsdkjskfdlgj" and the session-related constants.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;

public class FindUDPCipher extends GhidraScript {

    // WinSock functions that handle raw network I/O
    private static final String[] WINSOCK_NAMES = {
        "sendto", "send", "WSASend", "WSASendTo",
        "recvfrom", "recv", "WSARecv", "WSARecvFrom",
    };

    // Strings that might appear near the cipher
    private static final String[] NEEDLE_STRINGS = {
        "xfghsdkjskfdlgj",       // NetMgr password
        "WINSOCKMGR",             // socket manager class
        "Receive 0 Buffer",       // error we see in client log
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/udp_cipher_trace.txt";

    @Override
    protected void run() throws Exception {
        DecompInterface decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();
        SymbolTable symTab = currentProgram.getSymbolTable();
        FunctionManager fm = currentProgram.getFunctionManager();

        TreeMap<Address, Function> targets = new TreeMap<>();
        TreeMap<Address, StringBuilder> whyLog = new TreeMap<>();

        try (PrintWriter pw = new PrintWriter(OUT_PATH)) {
            pw.println("# UDP cipher trace — finding the REAL wire encryption");
            pw.println("# ObfuscateStreamBuf was proven NOT the UDP cipher.");
            pw.println("# This script traces WinSock send/recv callers and");
            pw.println("# searches for crypto-related string references.");
            pw.println();

            // ── Phase 1: Find WinSock send/recv imports ──────────
            pw.println("== Phase 1: WinSock imports ==");
            pw.println();
            for (String name : WINSOCK_NAMES) {
                for (Symbol sym : symTab.getSymbols(name)) {
                    Address addr = sym.getAddress();
                    pw.println("IMPORT " + name + " @ " + addr);
                    // Find all callers of this import
                    ReferenceIterator refs = refMgr.getReferencesTo(addr);
                    Set<Function> callers = new LinkedHashSet<>();
                    while (refs.hasNext()) {
                        Reference r = refs.next();
                        Function fn = listing.getFunctionContaining(
                                r.getFromAddress());
                        if (fn != null && !fn.isThunk()) {
                            callers.add(fn);
                        }
                        // Also check thunks
                        if (fn != null && fn.isThunk()) {
                            // Walk thunk callers
                            ReferenceIterator thunkRefs =
                                    refMgr.getReferencesTo(fn.getEntryPoint());
                            while (thunkRefs.hasNext()) {
                                Reference tr = thunkRefs.next();
                                Function caller = listing.getFunctionContaining(
                                        tr.getFromAddress());
                                if (caller != null) {
                                    callers.add(caller);
                                }
                            }
                        }
                    }
                    for (Function fn : callers) {
                        pw.println("  caller: " + fn.getName() + " @ "
                                + fn.getEntryPoint());
                        targets.putIfAbsent(fn.getEntryPoint(), fn);
                        whyLog.computeIfAbsent(fn.getEntryPoint(),
                                k -> new StringBuilder())
                              .append("  calls ").append(name)
                              .append(" @ ").append(addr).append("\n");
                    }
                    pw.println();
                }
            }
            println("Phase 1: found " + targets.size()
                    + " functions calling WinSock");

            // ── Phase 2: Find string references ──────────────────
            pw.println("== Phase 2: Crypto-related string references ==");
            pw.println();
            DataIterator dit = listing.getDefinedData(true);
            while (dit.hasNext()) {
                Data d = dit.next();
                if (d.getDataType() == null) continue;
                String dtName = d.getDataType().getName().toLowerCase();
                if (!dtName.contains("string") && !dtName.contains("char"))
                    continue;
                Object val = d.getValue();
                if (!(val instanceof String)) continue;
                String s = (String) val;
                for (String needle : NEEDLE_STRINGS) {
                    if (s.contains(needle)) {
                        pw.println("STRING '" + needle + "' @ "
                                + d.getAddress());
                        ReferenceIterator refs =
                                refMgr.getReferencesTo(d.getAddress());
                        while (refs.hasNext()) {
                            Reference r = refs.next();
                            Function fn = listing.getFunctionContaining(
                                    r.getFromAddress());
                            if (fn != null) {
                                pw.println("  ref from " + fn.getName()
                                        + " @ " + fn.getEntryPoint());
                                targets.putIfAbsent(fn.getEntryPoint(), fn);
                                whyLog.computeIfAbsent(fn.getEntryPoint(),
                                        k -> new StringBuilder())
                                      .append("  refs '").append(needle)
                                      .append("'\n");
                            }
                        }
                        pw.println();
                    }
                }
            }
            println("Phase 2: total " + targets.size()
                    + " functions after string search");

            // ── Phase 3: Decompile all targets ───────────────────
            pw.println();
            pw.println("== Phase 3: Decompilations (" + targets.size()
                    + " functions) ==");
            pw.println();
            for (Function fn : targets.values()) {
                pw.println("======================================================");
                pw.println("FUNCTION " + fn.getName() + " @ "
                        + fn.getEntryPoint());
                pw.println("======================================================");
                StringBuilder why = whyLog.get(fn.getEntryPoint());
                if (why != null) {
                    pw.println("Why matched:");
                    pw.print(why);
                    pw.println();
                }
                DecompileResults res = decomp.decompileFunction(
                        fn, 120, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted()) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("(decompile failed)");
                }
                pw.println();
            }
        }
        println("Wrote " + targets.size() + " functions to " + OUT_PATH);
    }
}
