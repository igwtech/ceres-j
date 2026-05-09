package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;

/**
 * Byte-identity test for {@link WorldNPCInfo}
 * (UDP S→C reliable {@code 0x03/0x28}, WorldInfo NPC variant).
 *
 * <p>Wire format (35-byte header + script_name\0 + model_name\0):
 *
 * <pre>
 *   [0..1]   00 01
 *   [2..3]   world_obj_id  LE16
 *   [4..5]   00 00
 *   [6..9]   world_instance_ref LE32 = 0x0088B3A7 (= 8,958,887) (8,958,887)
 *   [10..11] npc_type_id LE16
 *   [12..13] Y LE16
 *   [14..15] Z LE16
 *   [16..17] X LE16
 *   [18..19] 00 00
 *   [20]     0x22 (zone area)
 *   [21..34] 00 ×14
 *   [35..]   script_name\0 model_name\0
 * </pre>
 */
public class WorldNPCInfoByteIdentityTest {

    private static byte[] datagramBytes(WorldNPCInfo pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Body starts at offset 11 (PacketBuilderUDP1303). */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x28", 0x28, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void headerLayoutForKnownNpc() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        // mapID=257, type=191 (CityMercs guard), pos (1000, 2000, 3000)
        NPC npc = new NPC(1000, 2000, 3000, 100, 0, 191, 257);

        // Inner body length = 35 (header) + 0 ("") + 1 (null) + 0 ("") + 1 (null) = 37
        byte[] body = extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, npc)), 37);

        // [0..1] 00 01
        assertEquals(0x00, body[0] & 0xFF);
        assertEquals(0x01, body[1] & 0xFF);
        // [2..3] world_obj_id LE16 = 257 = 0x0101
        assertEquals(0x01, body[2] & 0xFF);
        assertEquals(0x01, body[3] & 0xFF);
        // [4..5] 00 00
        assertEquals(0x00, body[4] & 0xFF);
        assertEquals(0x00, body[5] & 0xFF);
        // [6..9] world_instance_ref LE32 = 0x0088B3A7 (= 8,958,887)
        assertEquals(0xA7, body[6] & 0xFF);
        assertEquals(0xB3, body[7] & 0xFF);
        assertEquals(0x88, body[8] & 0xFF);
        assertEquals(0x00, body[9] & 0xFF);
        // [10..11] npc_type_id LE16 = 191
        assertEquals(0xBF, body[10] & 0xFF);
        assertEquals(0x00, body[11] & 0xFF);
        // [12..13] Y LE16 = 2000 = 0x07D0
        assertEquals(0xD0, body[12] & 0xFF);
        assertEquals(0x07, body[13] & 0xFF);
        // [14..15] Z LE16 = 3000 = 0x0BB8
        assertEquals(0xB8, body[14] & 0xFF);
        assertEquals(0x0B, body[15] & 0xFF);
        // [16..17] X LE16 = 1000 = 0x03E8
        assertEquals(0xE8, body[16] & 0xFF);
        assertEquals(0x03, body[17] & 0xFF);
        // [18..19] 00 00
        assertEquals(0x00, body[18] & 0xFF);
        assertEquals(0x00, body[19] & 0xFF);
        // [20] 0x22 zone area
        assertEquals(0x22, body[20] & 0xFF);
        // [21..34] 14 zero bytes
        for (int i = 21; i <= 34; i++) {
            assertEquals("zero byte at offset " + i,
                    0x00, body[i] & 0xFF);
        }
    }

    @Test
    public void scriptAndModelNamesAtTrailingPosition() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(257, 1, 191,
                "Sergeant",
                "scripts/guard.lua",
                "models/guard_male",
                100, 200, 300, 0, 100, 0);

        // Header (35) + scriptName (17) + null (1) + modelName (17) + null (1) = 71B
        byte[] body = extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, npc)),
                35 + 17 + 1 + 17 + 1);

        // script_name "scripts/guard.lua" at offset 35
        byte[] scriptBytes = "scripts/guard.lua"
                .getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < scriptBytes.length; i++) {
            assertEquals("script byte " + i,
                    scriptBytes[i], body[35 + i]);
        }
        assertEquals("script null terminator",
                0x00, body[35 + 17] & 0xFF);

        // model_name at offset 35 + 18 = 53
        byte[] modelBytes = "models/guard_male"
                .getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < modelBytes.length; i++) {
            assertEquals("model byte " + i,
                    modelBytes[i], body[53 + i]);
        }
        assertEquals("model null terminator",
                0x00, body[53 + 17] & 0xFF);
    }

    @Test
    public void emptyScriptModelNamesProduceMinimalTrailer() {
        // Default NPC constructor leaves scriptName and modelName
        // empty — body should be exactly 37 bytes (35 header + 2
        // null terminators).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(0, 0, 0, 100, 0, 0, 1);

        byte[] body = extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, npc)), 37);
        assertEquals("byte at offset 35 (script null)",
                0x00, body[35] & 0xFF);
        assertEquals("byte at offset 36 (model null)",
                0x00, body[36] & 0xFF);
    }

    @Test
    public void coordinatesEncodeLittleEndian() {
        // Distinct values per axis — catches a swap.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(0xABCD, 0x1234, 0x5678, 100, 0, 1, 1);

        byte[] body = extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, npc)), 37);
        // X=0xABCD → at offset 16..17
        assertEquals(0xCD, body[16] & 0xFF);
        assertEquals(0xAB, body[17] & 0xFF);
        // Y=0x1234 → at offset 12..13
        assertEquals(0x34, body[12] & 0xFF);
        assertEquals(0x12, body[13] & 0xFF);
        // Z=0x5678 → at offset 14..15
        assertEquals(0x78, body[14] & 0xFF);
        assertEquals(0x56, body[15] & 0xFF);
    }

    @Test
    public void worldInstanceRefIsConstant() {
        // 0x008897A7 (8,958,887) is hardcoded — pin it so a
        // future refactor that derives it from zone state can't
        // accidentally drop the magic value.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(0, 0, 0, 100, 0, 0, 1);

        byte[] body = extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, npc)), 37);
        assertEquals(0xA7, body[6] & 0xFF);
        assertEquals(0xB3, body[7] & 0xFF);
        assertEquals(0x88, body[8] & 0xFF);
        assertEquals(0x00, body[9] & 0xFF);
    }
}
