// DisasmFlagSite.java — disassemble around the FUN_006fee00 call site in
// FUN_006ff980 (@0x7039b0) to find which struct field feeds the zone-flags word.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Instruction;
import java.io.PrintWriter;

public class DisasmFlagSite extends GhidraScript {
    private static final String OUT =
        "/home/javier/Documents/Projects/Neocron/ceres-j/docs/flag_site_disasm.txt";

    @Override
    protected void run() throws Exception {
        try (PrintWriter pw = new PrintWriter(OUT)) {
            // disassemble 0x60 bytes BEFORE the call through end of call
            Address call = currentProgram.getAddressFactory().getAddress("007039b0");
            Address a = call.subtract(0x70);
            for (int i = 0; i < 60; i++) {
                Instruction ins = getInstructionAt(a);
                if (ins == null) { a = a.add(1); continue; }
                String mark = ins.getAddress().equals(call) ? "   <<< CALL flag-decoder" : "";
                pw.printf("%s  %s%s%n", ins.getAddress(), ins.toString(), mark);
                Address nxt = ins.getFallThrough();
                if (nxt == null) nxt = ins.getAddress().add(ins.getLength());
                a = nxt;
                if (a.compareTo(call.add(0x10)) > 0) break;
            }
        }
        println("Wrote " + OUT);
    }
}
