// FindA0AndPosture.java — Ghidra headless script.
//
// (1) Find the TCP 0xa0 subsystem handler (InteractionAck a0/01,a0/02
//     — NOT handled by the 0x83 dispatcher FUN_0055aa30).
// (2) Find the UDP WWORLDMGR per-entity handler that applies the
//     0x03/0x1f subaction 0x17 (sit) / 0x21 (sit-broadcast) /
//     0x22 (stand) posture and triggers the chair-sit animation,
//     by string xref to posture/anim/"sit"/chair and the entity
//     vtable parse (vt+0x18 / vt+0x20 from FUN_00541f20 §1).
//
// Usage:
//   /opt/ghidra/support/analyzeHeadless \
//       /home/javier/Documents/Projects/Neocron Neocron2clien \
//       -process neocronclient.exe -noanalysis \
//       -scriptPath <thisdir> -postScript FindA0AndPosture.java

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

public class FindA0AndPosture extends GhidraScript {

    private static final String[] NEEDLES = new String[] {
        "sit", "Sit", "chair", "Chair", "posture", "Posture",
        "GAMEMASTERTOOL", "NETMSG_GAMEMASTERTOOL",
        "Use ", "interact", "Interact", "Anim", "anim",
        "stand", "Stand", "kneel", "Kneel",
        "Wrong Action", "action denied", "Action",
    };

    private static final String OUT_PATH =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/re_a0_posture_dump.txt";

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
            boolean any = false;
            while (ri.hasNext()) {
                Reference r = ri.next();
                Function f = listing.getFunctionContaining(r.getFromAddress());
                if (f != null) { hits.add(f); any = true; }
            }
            if (any) out.println("STR @ " + a + "  : "
                    + s.replace("\n", "\\n"));
        }

        out.println("\n==== Functions referencing posture/action/GM strings ====");
        for (Function f : hits) {
            out.println("\n----- " + f.getName() + " @ "
                    + f.getEntryPoint() + " -----");
            DecompileResults res =
                dec.decompileFunction(f, 60, new ConsoleTaskMonitor());
            if (res != null && res.getDecompiledFunction() != null) {
                out.println(res.getDecompiledFunction().getC());
            }
        }
        out.close();
        println("FindA0AndPosture: wrote " + OUT_PATH + " ("
                + hits.size() + " functions)");
    }
}
