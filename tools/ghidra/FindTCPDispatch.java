// FindTCPDispatch.java — Ghidra headless script.
//
// Locate the client's TCP protocol dispatcher (the 0xfe-framed
// [len LE2][subsystem][op] handler — distinct from the UDP 0x13/0x03
// reliable dispatcher FUN_0055ec10). netlib.dll is only Winsock
// transport (NETMGR/SRVNETMGR); the protocol switch lives in
// neocronclient.exe. We find it via:
//   1. log/string xrefs the TCP handlers print (NETMGR class names,
//      zone-path / worldclient strings, the SRVNETMGR receive path),
//   2. then dump decompilation of the function that switches on the
//      subsystem byte and the op byte for the 0x83/0x0c (Location),
//      0x83/0x0d (zone-clear), 0xa0/0x01/0x02 (InteractionAck) frames.
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient.exe -noanalysis \
//       -scriptPath /home/javier/Documents/Projects/Neocron/ceres-j/tools/ghidra \
//       -postScript FindTCPDispatch.java

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;

public class FindTCPDispatch extends GhidraScript {

    private static final String[] NEEDLES = new String[] {
        "NETMGR", "SRVNETMGR", "WINSOCKMGR",
        "WORLDCLIENT", "WorldClient",
        "Changepos", "World Change",
        "plaza/plaza", "worldchange",
        "Connecting to worldserver",
        "Synchronization with worldserver",
        "TCP", "tcp",
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_tcp_dispatch_dump.txt";

    @Override
    protected void run() throws Exception {
        PrintWriter out = new PrintWriter(OUT_PATH);
        DecompInterface dec = new DecompInterface();
        dec.setOptions(new DecompileOptions());
        dec.openProgram(currentProgram);

        Listing listing = currentProgram.getListing();
        DataIterator di = listing.getDefinedData(true);
        Set<Function> hits = new LinkedHashSet<>();

        while (di.hasNext()) {
            Data d = di.next();
            if (d == null) continue;
            Object v = d.getValue();
            if (!(v instanceof String)) continue;
            String s = (String) v;
            boolean match = false;
            for (String n : NEEDLES) {
                if (s.contains(n)) { match = true; break; }
            }
            if (!match) continue;

            Address a = d.getAddress();
            ReferenceIterator ri =
                currentProgram.getReferenceManager().getReferencesTo(a);
            while (ri.hasNext()) {
                Reference r = ri.next();
                Function f = listing.getFunctionContaining(r.getFromAddress());
                if (f != null) hits.add(f);
            }
            out.println("STR @ " + a + "  : " + s.replace("\n", "\\n"));
        }

        out.println("\n==== Functions referencing TCP/NETMGR/worldclient strings ====");
        for (Function f : hits) {
            out.println("\n----- " + f.getName() + " @ " + f.getEntryPoint()
                    + " -----");
            DecompileResults res =
                dec.decompileFunction(f, 60, new ConsoleTaskMonitor());
            if (res != null && res.getDecompiledFunction() != null) {
                out.println(res.getDecompiledFunction().getC());
            }
        }
        out.close();
        println("FindTCPDispatch: wrote " + OUT_PATH + " (" + hits.size()
                + " functions)");
    }
}
