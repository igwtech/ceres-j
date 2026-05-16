// MapLevelFileOffset.java — resolve the exact file offset of VA 0x00b05078
// using Ghidra's MemoryBlockSourceInfo + FileBytes, and verify by reading
// the byte through the FileBytes object (original file image).

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.mem.MemoryBlockSourceInfo;
import ghidra.program.database.mem.FileBytes;

public class MapLevelFileOffset extends GhidraScript {
    @Override
    protected void run() throws Exception {
        Address va = currentProgram.getAddressFactory().getAddress("00b05078");
        MemoryBlock blk = currentProgram.getMemory().getBlock(va);
        for (MemoryBlockSourceInfo si : blk.getSourceInfos()) {
            println("srcInfo desc=" + si.getDescription()
                    + " min=" + si.getMinAddress()
                    + " max=" + si.getMaxAddress()
                    + " fileBytesOffset=" + si.getFileBytesOffset());
            if (va.compareTo(si.getMinAddress()) >= 0
                    && va.compareTo(si.getMaxAddress()) <= 0
                    && si.getFileBytesOffset() >= 0) {
                long delta = va.subtract(si.getMinAddress());
                long fileOff = si.getFileBytesOffset() + delta;
                println("  -> VA in this block. delta=0x"
                        + Long.toHexString(delta)
                        + "  FILE OFFSET = " + fileOff
                        + " (0x" + Long.toHexString(fileOff) + ")");
                var fbOpt = si.getFileBytes();
                if (fbOpt != null && fbOpt.isPresent()) {
                    FileBytes fb = fbOpt.get();
                    println("  FileBytes name=" + fb.getFilename()
                            + " size=" + fb.getSize());
                    // Read 8 bytes at the original-file offset.
                    StringBuilder sb = new StringBuilder();
                    for (int i = -8; i < 8; i++) {
                        byte b = fb.getOriginalByte(fileOff + i);
                        sb.append(String.format(" %02x", b & 0xff));
                    }
                    println("  original file bytes [off-8 .. off+7]:" + sb);
                    println("  byte AT file offset = 0x"
                            + Integer.toHexString(
                                fb.getOriginalByte(fileOff) & 0xff));
                }
            }
        }
    }
}
