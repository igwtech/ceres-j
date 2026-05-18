package server.tools;

import static org.junit.Assert.*;

import org.junit.Test;

import server.networktools.ClientFrameDecoder;

/**
 * Unit tests for {@link WireLog} — the parsed in/out wire formatter
 * (task #198).
 *
 * <p>The 0x13 datagrams used here are hand-built to the retail wire
 * shape:
 * <pre>13 [octr LE2] [octr+sk LE2] ( [subLen LE2] [0x03] [seq LE2] [op] [data] )+</pre>
 * The same byte arrays are also fed to the
 * {@link ClientFrameDecoder} oracle to prove this formatter splits
 * sub-packets identically to the client (so the log shows exactly
 * what the client sees).
 */
public class WireLogFormatterTest {

    /** C→S 0x13 carrying one reliable 0x1f LocalChat (tag 0x1b)
     *  sub-packet: seq=7, localId=0x0102, tag=0x1b, body "hi". */
    private static byte[] csChatDatagram() {
        // inner sub = [03][seq LE2][1f][id LE2][1b]['h']['i']  = 8B
        byte[] sub = new byte[] {
            0x03, 0x07, 0x00,            // wrapper + seq=7
            0x1f, 0x02, 0x01, 0x1b,      // op 1f, localId=0x0102, tag 1b
            'h', 'i'
        };
        byte[] dg = new byte[5 + 2 + sub.length];
        dg[0] = 0x13;
        dg[1] = 0x2a; dg[2] = 0x00;      // counter
        dg[3] = 0x2b; dg[4] = 0x00;      // counter+sessionkey
        dg[5] = (byte) sub.length; dg[6] = 0x00; // subLen LE2
        System.arraycopy(sub, 0, dg, 7, sub.length);
        return dg;
    }

    /** S→C raw (non-0x13) UDPAlive-style datagram, op 0x01. */
    private static byte[] scRawDatagram() {
        return new byte[] { 0x01, (byte) 0xde, (byte) 0xad,
                (byte) 0xbe, (byte) 0xef };
    }

    @Test
    public void csUdpBlockHasDirectionPlayerOpAndTag() {
        String s = WireLog.formatUdp("C->S", "tester", csChatDatagram());
        assertTrue(s, s.contains("C->S UDP player=tester"));
        assertTrue(s, s.contains("0x13 outer: counter=42"));
        // 0x03 reliable wrapper, seq, op name.
        assertTrue(s, s.contains("wrapper=0x03"));
        assertTrue(s, s.contains("seq=7"));
        assertTrue(s, s.contains("op=0x1f (GamePackets)"));
        // 0x1f decoded fields: localId + tag name from the registry.
        assertTrue(s, s.contains("localId=258"));         // 0x0102
        assertTrue(s, s.contains("tag=0x1b (LocalChat)"));
        // body hex of "hi" present.
        assertTrue(s, s.contains("6869"));
    }

    @Test
    public void scRawUdpBlockNamesRawOpAndDumpsHex() {
        String s = WireLog.formatUdp("S->C", "bob", scRawDatagram());
        assertTrue(s, s.contains("S->C UDP player=bob len=5"));
        assertTrue(s, s.contains("raw datagram op=0x01"));
        assertTrue(s, s.contains("Handshake/ReliableAck"));
        assertTrue(s, s.contains("01deadbeef"));
    }

    @Test
    public void tcpBlockNamesOpcodeAndDumpsHex() {
        byte[] body = new byte[] { (byte) 0x83, 0x01, 0x11, 0x22 };
        String s = WireLog.formatTcp("C->S", "alice", body);
        assertTrue(s, s.contains("C->S TCP player=alice len=4"));
        assertTrue(s, s.contains("opcode=0x8301 (Auth/Account)"));
        assertTrue(s, s.contains("83011122"));
    }

    @Test
    public void nullPlayerRendersAsQuestionMark() {
        String s = WireLog.formatUdp("C->S", null, csChatDatagram());
        assertTrue(s, s.contains("player=?"));
    }

    @Test
    public void emptyDatagramIsHandled() {
        String s = WireLog.formatUdp("C->S", "x", new byte[0]);
        assertTrue(s, s.contains("(empty)"));
    }

    @Test
    public void hexDumpIsCappedSanely() {
        byte[] big = new byte[400];
        String d = WireLog.hexDump(big, 0, big.length);
        // 96-byte cap → 192 hex chars + truncation marker.
        assertTrue(d, d.contains("...(+304B)"));
        assertTrue(d.length() < 400 * 2);
    }

    /**
     * Fidelity guard: the formatter's 0x13 sub-packet split must
     * agree byte-for-byte with the {@link ClientFrameDecoder} oracle
     * (the decompiled-client framing model). If they ever diverge the
     * log would lie about what the client actually sees.
     */
    @Test
    public void splitMatchesClientFrameDecoderOracle() {
        byte[] dg = csChatDatagram();
        java.util.List<ClientFrameDecoder.Message> msgs =
                ClientFrameDecoder.decode(dg);
        assertEquals(1, msgs.size());
        ClientFrameDecoder.Message m = msgs.get(0);
        assertEquals(0x03, m.channel);
        assertEquals(7, m.seq);
        // Oracle's body = [op][data] = 1f 02 01 1b 68 69 (6B).
        assertEquals(6, m.body.length);
        assertEquals(0x1f, m.type());
        // Formatter must report the same seq + op for that sub.
        String s = WireLog.formatUdp("C->S", "t", dg);
        assertTrue(s, s.contains("seq=" + m.seq));
        assertTrue(s, s.contains("op=0x1f"));
    }

    @Test
    public void opAndTagNameRegistriesCoverKnownValues() {
        assertEquals("GamePackets", WireLog.opName(0x1f));
        assertEquals("Movement",    WireLog.opName(0x20));
        assertEquals("unknown",     WireLog.opName(0xAB));
        assertEquals("LocalChat",   WireLog.tagName(0x1b));
        assertEquals("AppAction",   WireLog.tagName(0x3d));
        assertEquals("unknown",     WireLog.tagName(0x99));
    }
}
