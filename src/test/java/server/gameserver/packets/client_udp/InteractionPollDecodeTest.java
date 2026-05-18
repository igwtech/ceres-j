package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Byte-identity decode test for {@link InteractionPoll} and
 * {@link ClientTelemetry3c} — the two raw (unreliable) C→S
 * sub-packets that task #188 added (previously logged as
 * {@code Unknown UDP13 Packet}).
 *
 * <p>Samples lifted verbatim from
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}.
 */
public class InteractionPollDecodeTest {

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s+", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(
                    h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    @Test
    public void interactionPollExtractsTargetId() {
        // C→S 1f 2b 01 55  (id 0x2b)
        InteractionPoll p = new InteractionPoll(hex("1f 2b 01 55"));
        assertEquals(0x2b, p.getTargetId());
    }

    @Test
    public void interactionPollAllRetailIds() {
        assertEquals(0xd5,
                new InteractionPoll(hex("1f d5 01 55")).getTargetId());
        assertEquals(0x0a,
                new InteractionPoll(hex("1f 0a 01 55")).getTargetId());
        assertEquals(0x0b,
                new InteractionPoll(hex("1f 0b 01 55")).getTargetId());
    }

    @Test
    public void interactionPollShortBodyIsNegativeOne() {
        assertEquals(-1, new InteractionPoll(hex("1f")).getTargetId());
    }

    @Test
    public void telemetry3cExtractsFields() {
        // C→S 3c 03 00 05 0b000000 006cae46
        //  seq=0x03, tag=0x05, value=0x0000000b (11),
        //  measure = float bits 0x46ae6c00 = 22326.0f
        ClientTelemetry3c t = new ClientTelemetry3c(
                hex("3c 03 00 05 0b 00 00 00 00 6c ae 46"));
        assertEquals(0x03, t.getSeq());
        assertEquals(0x05, t.getTag());
        assertEquals(11, t.getValue());
        assertEquals(22326.0f, t.getMeasure(), 0.0f);
    }

    @Test
    public void telemetry3cSignedValueDecodes() {
        // C→S 3c 01 00 09 feffffff 00c8ab45
        //  value = 0xfffffffe = -2 (signed),
        //  measure = float bits 0x45abc800 = 5497.0f
        ClientTelemetry3c t = new ClientTelemetry3c(
                hex("3c 01 00 09 fe ff ff ff 00 c8 ab 45"));
        assertEquals(0x01, t.getSeq());
        assertEquals(0x09, t.getTag());
        assertEquals(-2, t.getValue());
        assertEquals(5497.0f, t.getMeasure(), 0.0f);
    }

    @Test
    public void telemetry3cShortBodyIsDefensive() {
        ClientTelemetry3c t = new ClientTelemetry3c(hex("3c 03 00"));
        assertEquals(-1, t.getSeq());
        assertEquals(-1, t.getTag());
    }
}
