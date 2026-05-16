// DumpLogLevelAddr.java — pinpoint the file offset of the log-level default
// byte at VA 0x00b05078 so a byte patch can be specified exactly, and
// confirm the surrounding bytes / section.

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.MemoryBlock;

public class DumpLogLevelAddr extends GhidraScript {
    @Override
    protected void run() throws Exception {
        Address va = currentProgram.getAddressFactory().getAddress("00b05078");
        MemoryBlock blk = currentProgram.getMemory().getBlock(va);
        long imageBase = currentProgram.getImageBase().getOffset();
        println("imageBase = 0x" + Long.toHexString(imageBase));
        println("VA 0x00b05078  RVA 0x"
                + Long.toHexString(0xb05078L - imageBase));
        if (blk != null) {
            println("block name = " + blk.getName()
                    + "  start=" + blk.getStart()
                    + "  end=" + blk.getEnd()
                    + "  initialized=" + blk.isInitialized()
                    + "  w=" + blk.isWrite());
            // File offset: Ghidra exposes via getSourceInfos / address-to-file
            try {
                var infos = blk.getSourceInfos();
                for (var si : infos) {
                    println("  src: " + si.getDescription()
                            + "  minAddr=" + si.getMinAddress()
                            + "  fileBytesOffset="
                            + si.getFileBytesOffset());
                }
            } catch (Throwable t) {
                println("  (sourceInfos unavailable: " + t + ")");
            }
        }
        // PE: file offset = RVA - section.VirtualAddress + section.PointerToRawData.
        // Print the raw bytes 0x00b05070..0x00b0508f for the patch table.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 0x20; i++) {
            Address a = currentProgram.getAddressFactory()
                    .getAddress("00b05070").add(i);
            byte b = currentProgram.getMemory().getByte(a);
            sb.append(String.format("%s: %02x\n", a, b & 0xff));
        }
        println(sb.toString());
        println("level default byte = VA 0x00b05078 currently 0x"
                + Integer.toHexString(currentProgram.getMemory().getByte(va) & 0xff)
                + " (int " + currentProgram.getMemory().getInt(va) + ")");
    }
}
