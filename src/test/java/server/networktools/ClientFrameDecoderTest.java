package server.networktools;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.util.List;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.WorldNPCInfo;

/**
 * Pins the Ceres-J application-layer frame layout against the
 * byte-exact client model in {@link ClientFrameDecoder}.
 *
 * <p>Each test asserts the two invariants the client's WWORLDMGR
 * dispatcher ({@code FUN_00541f20}) enforces on every dequeued
 * ClientNetBuffer message:
 *
 * <ul>
 *   <li>{@code message.size == message.body.length} — the framed
 *       {@code Size:} the client prints equals the real body
 *       length (no {@code [0x03][seq]} bleed, no off-by-N);</li>
 *   <li>{@code message.body[0]} is the intended sub-opcode — the
 *       client reads the real Type, not an interior data byte
 *       (which is exactly how a misframe surfaces as
 *       {@code "Corrupted Message Type:15"}).</li>
 * </ul>
 *
 * <p>If a future framing change reintroduces the off-by-N, these
 * tests fail with the precise sub index and bytes rather than only
 * a runtime client log.
 */
public class ClientFrameDecoderTest {

    /** Single reliable sub: {@code [0x13][hdr]([subLen][03][seq][op][data])}. */
    @Test
    public void singleSubFramesExactly() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        PacketBuilderUDP1303 b = new PacketBuilderUDP1303(pl);
        b.write(0x28);                 // sub-opcode
        b.write(0xAA);
        b.write(0xBB);
        b.write(0xCC);

        List<ClientFrameDecoder.Message> msgs =
                ClientFrameDecoder.decode(
                        PacketTestFixture.flatten(
                                b.getDatagramPackets()));

        assertEquals("one application message", 1, msgs.size());
        ClientFrameDecoder.Message m = msgs.get(0);
        assertEquals("channel is the reliable wrapper 0x03",
                0x03, m.channel);
        assertEquals("body[0] is the intended sub-opcode 0x28",
                0x28, m.type());
        assertEquals("dequeued Size: equals real body length",
                m.body.length, m.size);
        assertArrayEquals("body = [op][data], no [03][seq] bleed",
                new byte[]{0x28, (byte) 0xAA, (byte) 0xBB,
                        (byte) 0xCC},
                m.body);
    }

    /**
     * Three reliable subs in one datagram (the post-zone-cross
     * init burst shape). Every message must independently satisfy
     * size==len and body[0]==its opcode — a single off-by-N in any
     * sub would slide every later sub's offset and turn an interior
     * byte into a bogus Type.
     */
    @Test
    public void multiSubEachFramedExactly() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        PacketBuilderUDP1303 b = new PacketBuilderUDP1303(pl);
        // sub 0: op 0x2c + 2 data
        b.write(0x2C);
        b.write(0x11);
        b.write(0x22);
        // sub 1: op 0x28 + 1 data
        b.newSubPacket();
        b.write(0x28);
        b.write(0x0F);   // interior 0x0F — must NOT become a Type
        // sub 2: op 0x1f + 3 data
        b.newSubPacket();
        b.write(0x1F);
        b.write(0x01);
        b.write(0x00);
        b.write(0x25);

        List<ClientFrameDecoder.Message> msgs =
                ClientFrameDecoder.decode(
                        PacketTestFixture.flatten(
                                b.getDatagramPackets()));

        assertEquals("three application messages", 3, msgs.size());

        int[] wantOp = {0x2C, 0x28, 0x1F};
        byte[][] wantBody = {
                {0x2C, 0x11, 0x22},
                {0x28, 0x0F},
                {0x1F, 0x01, 0x00, 0x25},
        };
        for (int k = 0; k < 3; k++) {
            ClientFrameDecoder.Message m = msgs.get(k);
            assertEquals("sub " + k + " body[0] is its opcode",
                    wantOp[k], m.type());
            assertEquals("sub " + k + " Size: == body length",
                    m.body.length, m.size);
            assertArrayEquals("sub " + k
                    + " body exact ([op][data], no bleed)",
                    wantBody[k], m.body);
            assertNotEquals("sub " + k
                    + " Type is never the interior 0x0F", 0x0F,
                    m.type());
        }
        // Decisively: the 0x0F we buried inside sub 1's data is
        // *data*, never read as a WWORLDMGR Type.
        assertEquals(0x0F, msgs.get(1).body[1] & 0xFF);
    }

    /**
     * A real {@link WorldNPCInfo} ({@code 0x03/0x28}) — the exact
     * "Corrupted Message Type:15" suspect — frames so the client
     * reads Type {@code 0x28}, with {@code Size:} == body length,
     * even when the NPC's body contains interior {@code 0x0F}
     * bytes.
     */
    @Test
    public void worldNpcInfoFramesAsType0x28() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        // id, zoneId, type, name, scriptName, modelName,
        // x, y, z, angle, hp, armor
        NPC npc = new NPC(0x0149, 1, 0x003b, "WSK", "WSK", "",
                100, 200, 300, 1, 100, 0);

        WorldNPCInfo p = new WorldNPCInfo(pl, npc);
        List<ClientFrameDecoder.Message> msgs =
                ClientFrameDecoder.decode(
                        PacketTestFixture.flatten(
                                p.getDatagramPackets()));

        assertEquals(1, msgs.size());
        ClientFrameDecoder.Message m = msgs.get(0);
        assertEquals("client reads WWORLDMGR Type 0x28 (WorldInfo)"
                + ", not a misframed interior byte",
                0x28, m.type());
        assertEquals("Size: == body length (no off-by-N)",
                m.body.length, m.size);
        assertNotEquals("never the phantom Type 0x0F",
                0x0F, m.type());
        // Body must start exactly with the documented constant
        // prefix 28 00 01 (catalog udp_s2c_03_28 / WorldNPCInfo).
        assertEquals(0x28, m.body[0] & 0xFF);
        assertEquals(0x00, m.body[1] & 0xFF);
        assertEquals(0x01, m.body[2] & 0xFF);
    }

    /**
     * Multipart {@code 0x03/0x07}: every fragment is itself a
     * reliable sub whose body[0] is {@code 0x07} and whose
     * {@code Size:} equals its body length. The reassembled
     * stream is the client reassembler's concern; the frame layer
     * (this test) only has to deliver each fragment with exact
     * size and a {@code 0x07} type byte.
     */
    @Test
    public void multipartFragmentsFrameExactly() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        server.networktools.PacketBuilderUDP130307 pb =
                new server.networktools.PacketBuilderUDP130307(pl);
        // Force multipart: > SINGLE_PACKET_THRESHOLD (900B).
        pb.newSection(1);
        for (int i = 0; i < 1500; i++) {
            pb.write(i & 0xFF);
        }
        DatagramPacket[] dps = pb.getDatagramPackets();
        assertTrue("body > 900B must fragment", dps.length > 1);

        for (int f = 0; f < dps.length; f++) {
            List<ClientFrameDecoder.Message> msgs =
                    ClientFrameDecoder.decode(
                            PacketTestFixture.flatten(
                                    new DatagramPacket[]{dps[f]}));
            assertEquals("fragment " + f
                    + " is one reliable sub", 1, msgs.size());
            ClientFrameDecoder.Message m = msgs.get(0);
            assertEquals("fragment " + f
                    + " body[0] is the 0x07 multipart sub-op",
                    0x07, m.type());
            assertEquals("fragment " + f
                    + " Size: == body length (no off-by-N)",
                    m.body.length, m.size);
            assertEquals("fragment " + f
                    + " channel is reliable 0x03", 0x03,
                    m.channel);
        }
    }

    /**
     * Single-packet {@code 0x03/0x2c} CharInfo (body ≤ 900B):
     * one reliable sub, body[0] == 0x2c, Size: == body length.
     */
    @Test
    public void singlePacketCharInfoFramesAsType0x2c() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        server.networktools.PacketBuilderUDP130307 pb =
                new server.networktools.PacketBuilderUDP130307(pl);
        pb.newSection(1);
        for (int i = 0; i < 64; i++) {
            pb.write(i & 0xFF);
        }
        DatagramPacket[] dps = pb.getDatagramPackets();
        assertEquals("small body stays single packet", 1,
                dps.length);

        List<ClientFrameDecoder.Message> msgs =
                ClientFrameDecoder.decode(
                        PacketTestFixture.flatten(dps));

        assertEquals(1, msgs.size());
        ClientFrameDecoder.Message m = msgs.get(0);
        assertEquals("body[0] is the 0x2c single-CharInfo sub-op",
                0x2C, m.type());
        assertEquals("Size: == body length (no off-by-N)",
                m.body.length, m.size);
    }
}
